package dev.gmlabs.jarvis

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ASSISTANT_OVERLAY = "assistant_overlay"
    }

    private lateinit var output: TextView
    private lateinit var modelSummary: TextView
    private lateinit var t2Endpoint: EditText
    private lateinit var t2Model: EditText
    private lateinit var t2ApiKey: EditText
    private lateinit var t3Model: EditText
    private lateinit var apiKey: EditText
    private lateinit var t3Switch: Switch
    private lateinit var privacySwitch: Switch
    private lateinit var dryRunSwitch: Switch
    private lateinit var prefs: JarvisPreferences
    private var tts: TextToSpeech? = null
    private var ttsEnabled = true
    private var speechRecognizer: SpeechRecognizer? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val requestInProgress = AtomicBoolean(false)
    private var pendingVoiceStart = false
    private var whisperModelPath: String? = null
    private var overlayMode = false
    private var thinkingIndicator: ProgressBar? = null

    private val assistantRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingVoiceStart) {
                pendingVoiceStart = false
                startRecognition()
            } else if (!granted) {
                output.text = "Microphone permission is required for voice commands."
            }
        }
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val modelFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val model = LocalModelManager(this).importModel(uri, "jarvis-model.gguf")
                prefs.importedModelPath = model.path
                output.text = "Imported local model:\n${model.name}\nJasSI will use it for T1."
            } catch (error: Exception) {
                output.text = "Could not import model: ${error.message}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = JarvisPreferences(this)
        overlayMode = intent.getBooleanExtra(EXTRA_ASSISTANT_OVERLAY, false)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        whisperModelPath = ensureWhisperModel()
        if (overlayMode) {
            configureAssistantWindow()
            setContentView(buildAssistantOverlay())
        } else {
            setContentView(buildScreen())
        }
        maybeRequestMicrophone()
        maybeRequestCamera()
        if (overlayMode) window.decorView.post { transcribeWithWhisper() }
    }

    private fun configureAssistantWindow() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            dimAmount = 0.35f
        }
    }

    private fun buildAssistantOverlay(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(30, 51, 83), Color.rgb(12, 22, 39))
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }
        panel.addView(label("JasSI", 22, Color.WHITE))
        panel.addView(label("Ask anything", 13, Color.LTGRAY))
        output = label("Listening...", 17, Color.WHITE).apply {
            setPadding(0, dp(14), 0, dp(10))
        }
        panel.addView(output)
        thinkingIndicator = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        panel.addView(thinkingIndicator, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val close = button("Close") { finish() }
        panel.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))
        return panel
    }

    private fun buildScreen(): View {
        val device = ModelCatalog.profile(this)
        val recommendation = ModelCatalog.recommendation(this)
        val selected = ModelCatalog.models.firstOrNull { it.id == prefs.selectedModelId }
            ?: recommendation
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundResource(dev.gmlabs.jarvis.R.drawable.background_jarvis)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(label("JasSI", 13, Color.rgb(113, 191, 255)))
        content.addView(label("Your private Android assistant", 28, Color.WHITE))
        content.addView(label("Choose how JasSI thinks. Faster tiers are always tried first.", 15, Color.LTGRAY))
        content.addView(space(18))
        content.addView(card("Device profile", "${device.summary}\nRecommended: ${recommendation.name}"))
        content.addView(card("Native backend", NativeBridge.backendDescription(applicationInfo.nativeLibraryDir) +
            "\nHexagon is used only in builds made with Qualcomm's Hexagon SDK; otherwise JasSI uses the CPU fallback."))
        output = label("JasSI is ready. Try “open Waterfox” or “explain this error”.", 15, Color.WHITE).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(28, 41, 65))
        }
        content.addView(output)

        content.addView(label("On-device model · T1", 19, Color.WHITE))
        content.addView(label("Select a Hugging Face model. JasSI recommends a size your phone can run comfortably.", 14, Color.LTGRAY))
        val modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ModelCatalog.models.map { "${it.name} · ${it.accelerator.label}" }
            )
            setSelection(ModelCatalog.models.indexOfFirst { it.id == selected.id }.coerceAtLeast(0))
            setOnItemSelectedListener(SimpleItemSelectedListener { position ->
                prefs.selectedModelId = ModelCatalog.models[position].id
                modelSummary.text = ModelCatalog.models[position].description +
                    "\nHugging Face: ${ModelCatalog.models[position].id}"
            })
        }
        content.addView(modelSpinner)
        modelSummary = label(
            selected.description + "\nHugging Face: ${selected.id}",
            13,
            Color.LTGRAY
        )
        content.addView(modelSummary)
        content.addView(button("Open Hugging Face model page") {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                "https://huggingface.co/${prefs.selectedModelId}"
            )))
        })
        content.addView(button("Load local model file (.gguf)") {
            modelFilePicker.launch(arrayOf(
                "application/octet-stream",
                "application/x-gguf",
                "application/vnd.safetensors",
                "application/*"
            ))
        })
        content.addView(label(
            if (prefs.importedModelPath.isBlank()) {
                "No local model file loaded. Select a compatible GGUF file from Hugging Face."
            } else {
                "Loaded local model: ${prefs.importedModelPath.substringAfterLast('/')}"
            },
            13,
            Color.LTGRAY
        ))

        content.addView(label("Optional network model · T2", 19, Color.WHITE))
        content.addView(label("Use Ollama or LM Studio on your home network. Leave blank to skip T2.", 14, Color.LTGRAY))
        t2Endpoint = EditText(this).apply {
            hint = "http://192.168.1.20:11434"
            setText(prefs.t2Endpoint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        content.addView(t2Endpoint)
        val t2Provider = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Ollama", "LM Studio")
            )
            setSelection(listOf("Ollama", "LM Studio").indexOf(prefs.t2Provider).coerceAtLeast(0))
            setOnItemSelectedListener(SimpleItemSelectedListener { position ->
                prefs.t2Provider = listOf("Ollama", "LM Studio")[position]
                t2Endpoint.hint = if (position == 0) {
                    "http://192.168.1.20:11434"
                } else {
                    "http://192.168.1.20:1234"
                }
            })
        }
        content.addView(t2Provider)
        t2Model = EditText(this).apply {
            hint = "Model name (for example llama3.2 or loaded LM Studio ID)"
            setText(prefs.t2Model)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        content.addView(t2Model)
        t2ApiKey = EditText(this).apply {
            hint = "Optional local-server API key"
            setText(prefs.t2ApiKey)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(t2ApiKey)
        content.addView(button("Save network model") {
            prefs.t2Endpoint = t2Endpoint.text.toString().trim()
            prefs.t2Model = t2Model.text.toString().trim()
            prefs.t2ApiKey = t2ApiKey.text.toString()
            output.text = if (prefs.t2Endpoint.isBlank()) "T2 disabled." else "T2 enabled for ${prefs.t2Endpoint}."
        })
        content.addView(button("Test T2 connection and list models") {
            prefs.t2Endpoint = t2Endpoint.text.toString().trim()
            prefs.t2Model = t2Model.text.toString().trim()
            prefs.t2ApiKey = t2ApiKey.text.toString()
            if (prefs.t2Endpoint.isBlank()) {
                output.text = "Enter the computer's LAN address first. Do not use localhost from the phone."
            } else if (prefs.t2Endpoint.contains("localhost", ignoreCase = true) ||
                prefs.t2Endpoint.contains("127.0.0.1")) {
                output.text = "A phone cannot reach the computer through localhost. Replace it with the computer's LAN IP address."
            } else {
                output.text = "Testing ${prefs.t2Provider}..."
                worker.execute {
                    val message = runCatching {
                        ProviderDiscovery.local(prefs.t2Provider, prefs.t2Endpoint, prefs.t2ApiKey)
                    }.getOrElse { "T2 test failed: ${it.message ?: "connection failed"}" }
                    runOnUiThread { output.text = message }
                }
            }
        })
        content.addView(label(
            "Ollama: use the computer's address and port 11434. LM Studio: enable Developer > Start server, " +
                "allow network access, and use port 1234. JasSI discovers the model IDs for you.",
            13,
            Color.LTGRAY
        ))

        content.addView(label("Optional online model · T3", 19, Color.WHITE))
        t3Switch = Switch(this).apply {
            text = "Allow OpenAI / Google / Claude API"
            setTextColor(Color.WHITE)
            isChecked = prefs.t3Enabled
            setOnCheckedChangeListener { _, enabled -> prefs.t3Enabled = enabled }
        }
        content.addView(t3Switch)
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenAI", "Google", "Claude")
            )
            setSelection(listOf("OpenAI", "Google", "Claude").indexOf(prefs.t3Provider).coerceAtLeast(0))
            setOnItemSelectedListener(SimpleItemSelectedListener { position ->
                prefs.t3Provider = listOf("OpenAI", "Google", "Claude")[position]
            })
        }
        content.addView(providerSpinner)
        t3Model = EditText(this).apply {
            hint = "Cloud model name"
            setText(prefs.t3Model)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        content.addView(t3Model)
        apiKey = EditText(this).apply {
            hint = "API key for selected cloud provider"
            setText(currentApiKey())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(apiKey)
        content.addView(button("Save cloud settings") {
            prefs.t3Model = t3Model.text.toString().trim()
            saveCurrentApiKey(apiKey.text.toString())
            output.text = "Saved ${prefs.t3Provider} settings on this device."
        })
        content.addView(button("Check cloud API key") {
            prefs.t3Model = t3Model.text.toString().trim()
            saveCurrentApiKey(apiKey.text.toString())
            output.text = "Checking ${prefs.t3Provider}..."
            worker.execute {
                val message = runCatching {
                    ProviderDiscovery.cloud(prefs.t3Provider, currentApiKey())
                }.getOrElse { "T3 check failed: ${it.message ?: "request failed"}" }
                runOnUiThread { output.text = message }
            }
        })
        content.addView(label("Cloud calls go directly from JasSI to the selected provider. No gateway is required. " +
            "Privacy mode blocks them.", 13, Color.LTGRAY))

        content.addView(space(8))
        privacySwitch = Switch(this).apply {
            text = "Privacy mode (block T3 cloud requests)"
            setTextColor(Color.WHITE)
            isChecked = prefs.privacyMode
            setOnCheckedChangeListener { _, enabled -> prefs.privacyMode = enabled }
        }
        dryRunSwitch = Switch(this).apply {
            text = "Dry-run actions (preview before execution)"
            setTextColor(Color.WHITE)
            isChecked = prefs.dryRun
            setOnCheckedChangeListener { _, enabled -> prefs.dryRun = enabled }
        }
        content.addView(privacySwitch)
        content.addView(dryRunSwitch)
        content.addView(button("Make JasSI my default assistant") { requestAssistantRole() })
        content.addView(button("Open Android default-assistant settings") {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        })
        content.addView(button("Open accessibility settings for dry-run actions") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(label(
            "Android does not grant accessibility or default-assistant access through a popup. " +
                "Use the buttons above, select JasSI in system settings, then return here.",
            13,
            Color.LTGRAY
        ))

        val input = EditText(this).apply {
            hint = "Ask JasSI anything..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val send = button("Send") { handle(input.text.toString(), false) }
        val voice = button("Voice") { listen() }
        val offlineVoice = button("Offline Whisper") { transcribeWithWhisper() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))
            addView(send)
            addView(voice)
            addView(offlineVoice)
        }
        content.addView(space(14))
        content.addView(controls)
        val ttsSwitch = Switch(this).apply {
            text = "Spoken replies"
            setTextColor(Color.WHITE)
            isChecked = ttsEnabled
            setOnCheckedChangeListener { _, enabled -> ttsEnabled = enabled }
        }
        content.addView(ttsSwitch)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun handle(text: String, isVoice: Boolean) {
        if (text.isBlank()) return
        if (!requestInProgress.compareAndSet(false, true)) {
            output.text = "JasSI is still processing the previous request."
            return
        }
        output.text = "Working..."
        thinkingIndicator?.apply {
            visibility = View.VISIBLE
            alpha = 0.4f
            animate().alpha(1f).setDuration(450).withEndAction {
                if (requestInProgress.get()) animate().alpha(0.4f).setDuration(450).start()
            }.start()
        }
        worker.execute {
            try {
                handleBlocking(text, isVoice)
            } finally {
                requestInProgress.set(false)
                runOnUiThread {
                    thinkingIndicator?.visibility = View.GONE
                }
            }
        }
    }

    private fun handleBlocking(text: String, isVoice: Boolean) {
        val settings = JarvisSettings(
            privacyMode = prefs.privacyMode,
            ttsEnabled = ttsEnabled,
            dryRun = prefs.dryRun,
            allowedActions = setOf("timer", "flashlight", "open_settings", "open_app")
        )
        val processors = mutableListOf<TierProcessor>(RuleProcessor(this, settings))
        val local = ModelCatalog.models.firstOrNull { it.id == prefs.selectedModelId }
        if (prefs.importedModelPath.isNotBlank()) {
            processors += object : TierProcessor {
                override val tier = Tier.T1
                override fun canHandle(input: String) = input.isNotBlank()
                override fun process(context: RequestContext): RouteResult {
                    val text = NativeBridge.generate(
                        prefs.importedModelPath,
                        context.input
                    ) ?: throw TierUnavailableException("Local inference is unavailable.")
                    return RouteResult(context.requestId, tier, text)
                }
            }
        } else if (local != null) {
            processors += object : TierProcessor {
                override val tier = Tier.T1
                override fun canHandle(input: String) = input.isNotBlank()
                override fun process(context: RequestContext): RouteResult {
                    val manager = LocalModelManager(this@MainActivity)
                    if (!manager.isDownloaded(local)) {
                        manager.download(local)
                        throw TierUnavailableException("Downloading ${local.name}; retry after it completes.")
                    }
                    val text = NativeBridge.generate(
                        manager.modelDirectory(local).resolve("model.gguf").path,
                        context.input
                    ) ?: throw TierUnavailableException("Local inference is unavailable.")
                    return RouteResult(context.requestId, tier, text)
                }
            }
        }
        if (prefs.t2Endpoint.isNotBlank()) {
            processors += OllamaProcessor(
                prefs.t2Endpoint,
                prefs.t2Model,
                prefs.t2Provider,
                prefs.t2ApiKey
            )
        }
        if (prefs.t3Enabled && !prefs.privacyMode) {
            val key = currentApiKey()
            if (key.isNotBlank()) {
                processors += CloudProcessor(prefs.t3Provider, prefs.t3Model, key)
            }
        }
        val result = try {
            JarvisRouter(settings, processors).route(RequestContext(input = text, isVoice = isVoice))
        } catch (error: Exception) {
            RouteResult("", Tier.T0, "I could not execute that: ${error.message ?: "unknown error"}")
        }
        runOnUiThread {
            output.text = "[${result.tier.label}] ${result.response}\n\nLatency: ${result.latencyMs} ms · Cost: ${result.estimatedCostCents}¢"
            if (ttsEnabled && result.response.length < 240) {
                tts?.speak(result.response, TextToSpeech.QUEUE_FLUSH, null, result.requestId)
            }
        }
    }

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceStart = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecognition()
    }

    private fun ensureWhisperModel(): String? {
        val destination = File(filesDir, "models/ggml-base-q5_1.bin")
        if (destination.isFile && destination.length() > 50L * 1024L * 1024L) {
            return destination.path
        }
        return runCatching {
            destination.parentFile?.mkdirs()
            assets.open("models/ggml-base-q5_1.bin").use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            destination.path
        }.getOrNull()
    }

    private fun transcribeWithWhisper() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceStart = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val model = whisperModelPath
        if (model.isNullOrBlank()) {
            output.text = "Offline Whisper model is not available."
            return
        }
        if (!requestInProgress.compareAndSet(false, true)) {
            output.text = "JasSI is still processing the previous request."
            return
        }
        output.text = "Listening offline for 6 seconds..."
        worker.execute {
            val result = runCatching {
                val sampleRate = 16_000
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                require(minBuffer > 0) { "This phone cannot open a microphone recorder." }
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, sampleRate)
                )
                require(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    "The microphone recorder could not be initialized."
                }
                val pcm = ShortArray(sampleRate * 6)
                try {
                    recorder.startRecording()
                    require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "The microphone did not enter recording mode."
                    }
                    var offset = 0
                    while (offset < pcm.size) {
                        val read = recorder.read(pcm, offset, pcm.size - offset)
                        if (read < 0) {
                            throw IllegalStateException("Microphone read failed: $read")
                        }
                        if (read == 0) continue
                        offset += read
                    }
                    require(offset > sampleRate / 4) { "No microphone audio was captured." }
                    val samples = FloatArray(offset) { index -> pcm[index] / 32768.0f }
                    NativeBridge.transcribe(model, samples).trim()
                } finally {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                    recorder.release()
                }
            }.getOrElse { "Offline speech recognition failed: ${it.message ?: "unknown error"}" }
            runOnUiThread {
                requestInProgress.set(false)
                if (result.isBlank()) {
                    output.text = "Whisper did not detect speech."
                } else {
                    output.text = "Whisper: $result"
                    handle(result, true)
                }
            }
        }
    }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            output.text = "Android has no speech-recognition service enabled. Install or enable an offline/online speech service, then try again."
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask JasSI")
        }
        speechRecognizer?.cancel()
        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { handle(it, true) }
                }
                override fun onError(error: Int) {
                    val reason = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "microphone audio"
                        SpeechRecognizer.ERROR_CLIENT -> "Android speech service"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network or speech service"
                        SpeechRecognizer.ERROR_NO_MATCH -> "no speech was understood"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "speech recognizer is busy"
                        SpeechRecognizer.ERROR_SERVER -> "speech server"
                        else -> "unknown speech error ($error)"
                    }
                    output.text = "Speech recognition failed: $reason."
                }
                override fun onReadyForSpeech(params: Bundle?) { output.text = "Listening..." }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        speechRecognizer?.startListening(intent)
    }

    private fun currentApiKey(): String = when (prefs.t3Provider.lowercase()) {
        "openai" -> prefs.openAiApiKey
        "google" -> prefs.googleApiKey
        "claude" -> prefs.claudeApiKey
        else -> ""
    }

    private fun saveCurrentApiKey(value: String) {
        when (prefs.t3Provider.lowercase()) {
            "openai" -> prefs.openAiApiKey = value
            "google" -> prefs.googleApiKey = value
            "claude" -> prefs.claudeApiKey = value
        }
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    assistantRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                } else if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    output.text = "JasSI is already your default assistant."
                } else {
                    output.text = "This Android build does not expose the assistant role. " +
                        "Open Android default-assistant settings and check whether JasSI is listed."
                }
        } else {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
    }

    private fun maybeRequestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun maybeRequestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun label(text: String, size: Int, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun card(title: String, body: String) = label("$title\n$body", 15, Color.WHITE).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setBackgroundColor(Color.rgb(28, 41, 65))
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }
}

private class SimpleItemSelectedListener(
    private val select: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = select(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
