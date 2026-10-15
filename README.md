# JasSI

JasSI is a privacy-first Android voice and text assistant with a cost-aware
processing router:

- **T0**: Android rules and APIs for deterministic actions.
- **T1**: a local model through the NPU/CPU/GPU bridge.
- **T2**: an Ollama or LM Studio endpoint on the home network.
- **T3**: direct optional OpenAI, Google Gemini, or Anthropic Claude calls from
  the Android app.

The implementation includes the Android shell, a model-selection UI
with device-aware Hugging Face recommendations, model downloads, local/native
inference seam, Android speech recognition and TTS, default-assistant
integration, a typed four-tier router, privacy and dry-run controls, direct
Ollama and LM Studio requests, a native C++ bridge, and editable cloud API
credentials. Network and model work runs off the Android UI thread.

T2 and T3 are optional: T2 is enabled by selecting Ollama or LM Studio,
entering the computer's LAN endpoint and model name, while T3 is enabled
explicitly and remains blocked by Privacy mode. Ollama uses its native
`/api/chat` endpoint; LM Studio uses its OpenAI-compatible
`/v1/chat/completions` endpoint. For LAN access, configure the desktop
provider to listen on the network and allow the port through its firewall.
Select **Make JasSI my default assistant** in the app to request Android's
assistant role. Microphone and camera access are requested when needed. Accessibility and the
default-assistant role are special Android settings, not runtime permissions:
use JasSI's setup buttons, select JasSI in the system screen, and return to
the app. Android will not allow Jarvis to grant either sensitive access to
itself programmatically.

## Android

Open the repository in Android Studio and run the `app` configuration. The
current UI supports text and speech input, tier display, spoken replies, model
download selection, deterministic T0 actions, optional Ollama/LM Studio
configuration, provider selection, privacy, and dry-run settings. T1 model
files can be downloaded from the selected Hugging Face repository or imported
with **Load local model file (.gguf)**. The Qwen recommendation downloads the
official Qwen GGUF quantization repository rather than the Safetensors-only
checkpoint. Safetensors checkpoints can be imported for identification, but
are not directly runnable by the native bridge. GGUF inference now uses the
official llama.cpp Android C++ backend, built for arm64-v8a and x86_64.
The C++ bridge is the integration point for a production llama.cpp/Qualcomm
backend.

If “open Waterfox” does not launch anything, use the exact installed app label
and make sure JasSI is not in dry-run mode. T0 does not require accessibility
or model permissions for opening apps and Settings; those permissions are only
needed for future UI automation and assistant invocation.

The `gateway/` directory is retained as an optional development/test adapter,
but the Android app does not depend on it and does not send requests through
it. Cloud keys are entered in JasSI's settings and stored in the app's private
preferences; use Privacy mode to block all cloud calls.
