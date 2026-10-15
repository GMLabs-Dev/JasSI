# JasSI

JasSI is a privacy-first voice and text assistant for Android. It routes each
request through the cheapest and fastest available processing tier, starting
with deterministic Android actions and escalating only when necessary.

<p align="center">
  <video src=".github/video.mp4" controls width="360">
    JasSI demo video
  </video>
</p>

> Demonstration recording at [`/.github/video.mp4`](.github/video.mp4).

## Highlights

- Four processing tiers with automatic fallback.
- Offline-first voice input using bundled Whisper.cpp.
- Spoken replies using Android Text-to-Speech.
- Compact assistant overlay for default-assistant invocation.
- Local GGUF model import and Hugging Face recommendations.
- Optional Ollama and LM Studio support over a home network.
- Optional direct OpenAI, Google Gemini, and Anthropic Claude support.
- Privacy mode that blocks cloud requests.
- Dry-run mode and accessibility integration for sensitive actions.
- Editable provider endpoints, models, and API keys in the app.
- Request statistics including tier, latency, and estimated cost.

## Processing tiers

| Tier | Processor | Typical use |
| --- | --- | --- |
| **T0** | Android rules and APIs | Open apps, timers, flashlight, Settings |
| **T1** | Local GGUF model through the native C++ bridge | Fuzzy commands and private questions |
| **T2** | Ollama or LM Studio on the home network | Summaries, explanations, and short code |
| **T3** | Direct cloud API from Android | Larger reasoning, generation, and refactoring |

The router tries T0 first. T2 and T3 are optional; JasSI can operate without a
gateway, LAN model, cloud account, or internet connection.

## Voice experience

JasSI supports typed and spoken requests through the same router:

1. Audio is captured locally with `AudioRecord`.
2. Whisper.cpp transcribes the recording on the phone.
3. The transcription is sent through T0, T1, T2, or T3.
4. The result is shown in the UI.
5. Voice-originated responses are automatically spoken with Android TTS when
   the response is short enough to read aloud.

Long responses remain visual so that code and detailed explanations are not
read word-for-word. The bundled Whisper model is copied into the app's private
storage on first launch.

Android's platform `SpeechRecognizer` is also supported when a speech service
is installed. The offline Whisper button and assistant overlay do not depend on
that service.

## Assistant overlay

JasSI declares both an `ASSIST` activity and a
`VoiceInteractionService`. To use the compact bottom overlay:

1. Open JasSI.
2. Tap **Make JasSI my default assistant**.
3. Approve JasSI in Android's assistant-role dialog.
4. If Android does not show the dialog, open **Default apps > Digital
   assistant app** and select JasSI.
5. Invoke the assistant with the device's configured assistant gesture or
   power-button shortcut.

Android does not allow an app to grant itself the default-assistant role or
Accessibility Service access. Those choices must be made in system settings.

## Local models

T1 accepts **GGUF** files. Safetensors checkpoints are not directly runnable by
the native llama.cpp bridge and must first be converted to GGUF.

To load a model:

1. Download a compatible GGUF quantization from Hugging Face.
2. Open JasSI and choose **Load local model file (.gguf)**.
3. Select the file.
4. Send a test prompt such as `Explain what a timer is`.

The model catalog recommends compact models based on available device memory.
The default Qwen recommendation points to the official Qwen GGUF repository,
not the Safetensors-only checkpoint.

The native bridge currently requests all possible layer offload, but the
runtime only reports an accelerator when a usable backend is actually
registered. The tested phone currently reports CPU. Hexagon support remains
optional and device-specific; JasSI must not claim NPU or GPU execution unless
the runtime backend reports it.

## Ollama and LM Studio

T2 runs directly from the phone to a model server on the local network.

### Ollama

1. Install and run Ollama on the computer.
2. Make Ollama listen on the LAN interface.
3. Ensure the firewall allows port `11434`.
4. Load a model, for example `llama3.2`.
5. In JasSI, select **Ollama** and enter:

   ```text
   http://COMPUTER_LAN_IP:11434
   ```

### LM Studio

1. Load a model in LM Studio.
2. Open **Developer** and start the local server.
3. Enable network access if the option is available.
4. Ensure the firewall allows port `1234`.
5. In JasSI, select **LM Studio** and enter:

   ```text
   http://COMPUTER_LAN_IP:1234
   ```

Do not use `localhost` or `127.0.0.1`: from the phone, those addresses refer to
the phone itself. JasSI discovers Ollama models through `/api/tags` and LM
Studio models through `/v1/models`.

## Cloud providers

T3 is disabled unless explicitly enabled. JasSI calls providers directly from
Android; the Python gateway is not required.

Supported providers:

- OpenAI
- Google Gemini
- Anthropic Claude

Provider model names and API keys are editable in the settings screen. Keys
are stored in the app's private preferences. Enable **Privacy mode** to block
all T3 requests regardless of the configured key.

## Permissions and safety

JasSI may request:

- Microphone: offline STT and voice commands.
- Camera: optional camera-related actions.
- Internet and network state: T2/T3 connectivity.
- Accessibility Service: dry-run UI automation and simulated taps/typing.
- Default assistant role: assistant gesture and overlay invocation.

Accessibility and the default-assistant role are special Android controls and
must be enabled manually in system settings. T0 actions such as opening an app
do not require Accessibility access.

Sensitive actions should remain allow-listed and confirmed. Dry-run mode shows
what JasSI would do before executing an accessibility action.

## Building

### Requirements

- Android Studio or Gradle.
- Android SDK with API 34.
- Android NDK `26.1.10909125`.
- Android CMake `3.22.1`.
- A device or emulator using `arm64-v8a` or `x86_64`.

Build the debug APK:

```bash
gradle :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The native module uses llama.cpp and Whisper.cpp. The upstream Hexagon backend
is enabled only when a compatible Qualcomm Hexagon SDK is available at build
time. Qualcomm SDK and HTP runtime components are not redistributed here.

## Project structure

```text
app/
  src/main/kotlin/dev/gmlabs/jarvis/
    MainActivity.kt                 # UI, overlay, STT, TTS, setup
    Router.kt                       # T0-T3 routing and fallback
    ModelCatalog.kt                 # device profiling and recommendations
    NetworkClients.kt               # Ollama, LM Studio, and cloud clients
    NativeBridge.kt                 # Kotlin/JNI boundary
    JarvisVoiceInteractionService.kt
  src/main/cpp/
    native_bridge.cpp               # llama.cpp and Whisper.cpp JNI code
    CMakeLists.txt                  # native build configuration
  src/main/assets/models/           # bundled Whisper model
gateway/                            # optional development/test adapter
.github/video.mp4                   # project demonstration video
```

## Troubleshooting

### The model does not load

Confirm that the selected file is a complete `.gguf` file. A Hugging Face
webpage, `.safetensors` checkpoint, or partial download will not work.

### JasSI says “Working...” indefinitely

Try a T0 command such as `open Firefox`. For T1, use a small quantized GGUF
model and inspect logcat:

```bash
adb logcat -s JasSI-GGUF
```

For T2, use the computer's LAN IP and press **Test T2 connection and list
models**.

### Voice recognition does not understand speech

Confirm microphone access, speak close to the phone, and use the offline
Whisper path. Short, clear recordings are more reliable than long or noisy
ones. If Android's speech service is unavailable, the offline path remains
self-contained.

### T0 cannot open an app

Use the installed package name or the visible app label. T0 does not need a
local model, cloud provider, or Accessibility Service for ordinary app
launching.

## Attribution

JasSI integrates the open-source llama.cpp and Whisper.cpp projects:

- [llama.cpp](https://github.com/ggml-org/llama.cpp)
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)

The optional Hexagon integration follows the upstream llama.cpp implementation:

<https://github.com/ggml-org/llama.cpp/tree/master/ggml/src/ggml-hexagon>

See [LICENSE](LICENSE) for this repository's license.
