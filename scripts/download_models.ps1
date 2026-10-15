$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force models | Out-Null

# Whisper.cpp
if (-not (Test-Path "models\ggml-small.bin")) {
    curl.exe -L -o models\ggml-small.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin
}

# Kokoro
pip install -U huggingface_hub
hf download hexgrad/Kokoro-82M --local-dir models\kokoro
