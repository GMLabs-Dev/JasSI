package dev.gmlabs.jarvis

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.CountDownTimer
import android.hardware.camera2.CameraManager
import android.widget.Toast
import java.util.UUID
import kotlin.math.max

enum class Tier(val label: String, val rank: Int) {
    T0("On-device rules", 0),
    T1("On-device model", 1),
    T2("Home network model", 2),
    T3("Cloud provider", 3)
}

data class JarvisSettings(
    val privacyMode: Boolean = true,
    val dryRun: Boolean = false,
    val ttsEnabled: Boolean = true,
    val cloudBudgetCents: Int = 100,
    val allowedActions: Set<String> = setOf("timer", "flashlight", "open_settings", "open_app")
)

data class RequestContext(
    val requestId: String = UUID.randomUUID().toString(),
    val input: String,
    val isVoice: Boolean = false
)

data class RouteResult(
    val requestId: String,
    val tier: Tier,
    val response: String,
    val action: String? = null,
    val previewOnly: Boolean = false,
    val latencyMs: Long = 0,
    val estimatedCostCents: Int = 0
)

class TierUnavailableException(message: String) : Exception(message)

interface TierProcessor {
    val tier: Tier
    fun canHandle(input: String): Boolean
    fun process(context: RequestContext): RouteResult
}

class RuleProcessor(private val androidContext: Context, private val settings: JarvisSettings) :
    TierProcessor {
    override val tier = Tier.T0

    override fun canHandle(input: String): Boolean {
        val command = normalize(input)
        return command.contains("timer") || command.contains("flashlight") ||
            command.contains("settings") || command.startsWith("open ")
    }

    override fun process(context: RequestContext): RouteResult {
        val started = System.currentTimeMillis()
        val command = normalize(context.input)
        val action = when {
            command.contains("flashlight") -> "flashlight"
            command.contains("settings") -> "open_settings"
            command.startsWith("open ") -> "open_app"
            else -> "timer"
        }
        val response = if (action !in settings.allowedActions) {
            "I need confirmation before I can execute $action."
        } else if (settings.dryRun) {
            "Dry run: I would execute $action."
        } else {
            execute(action, command)
        }
        return RouteResult(
            requestId = context.requestId,
            tier = tier,
            response = response,
            action = action,
            previewOnly = settings.dryRun,
            latencyMs = max(0, System.currentTimeMillis() - started)
        )
    }

    private fun execute(action: String, command: String): String {
        when (action) {
            "open_settings" -> androidContext.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "open_app" -> {
                val name = command.removePrefix("open ")
                    .removePrefix("the app ")
                    .removePrefix("app ")
                    .trim()
                val aliases = mapOf("waterfox" to "net.waterfox.android.releses")
                val target = aliases[name] ?: name
                return AppResolver(androidContext).launch(target)
            }

            "flashlight" -> {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        androidContext,
                        android.Manifest.permission.CAMERA
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) return "Camera permission is required to control the flashlight."
                val manager = androidContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(
                        android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                    ) == true
                } ?: return "This phone has no controllable flashlight."
                val enabled = command.contains("on") || !command.contains("off")
                manager.setTorchMode(cameraId, enabled)
                return "Flashlight turned ${if (enabled) "on" else "off"}."
            }
            "timer" -> {
                val timer = Regex("""(\d+)\s*(second|minute|hour)""").find(command)
                val amount = timer?.groupValues?.get(1)?.toLongOrNull() ?: 5
                val multiplier = when (timer?.groupValues?.get(2)) {
                    "hour" -> 3_600_000L
                    "minute" -> 60_000L
                    else -> 1_000L
                }
                object : CountDownTimer(amount * multiplier, amount * multiplier) {
                    override fun onTick(millisUntilFinished: Long) = Unit
                    override fun onFinish() = Toast.makeText(androidContext, "JasSI timer finished", Toast.LENGTH_LONG).show()
                }.start()
                return "Timer set for $amount ${timer?.groupValues?.get(2) ?: "minutes"}."
            }
        }
        return "Done."
    }

    private fun normalize(input: String): String = input
        .lowercase()
        .replace(Regex("[.!?,]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .removePrefix("hey jarvis ")
        .removePrefix("jarvis ")
        .removePrefix("please ")
}

class FallbackProcessor(
    override val tier: Tier,
    private val description: String
) : TierProcessor {
    override fun canHandle(input: String) = input.isNotBlank()

    override fun process(context: RequestContext) = RouteResult(
        requestId = context.requestId,
        tier = tier,
        response = "$description is configured as the next processing tier. " +
            "Connect its adapter to enable this request.",
        latencyMs = 0
    )
}

class JarvisRouter(
    private val settings: JarvisSettings,
    private val processors: List<TierProcessor>
) {
    fun route(context: RequestContext): RouteResult {
        val candidates = processors
            .filter { !(settings.privacyMode && it.tier == Tier.T3) }
            .sortedBy { it.tier.rank }
        var lastError: String? = null
        for (processor in candidates) {
            if (!processor.canHandle(context.input)) continue
            try {
                return processor.process(context)
            } catch (error: TierUnavailableException) {
                lastError = error.message
            }
        }
        return RouteResult(
            context.requestId,
            candidates.lastOrNull()?.tier ?: Tier.T0,
            lastError ?: "No processing tier is configured."
        )
    }
}
