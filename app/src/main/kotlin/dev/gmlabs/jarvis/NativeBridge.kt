package dev.gmlabs.jarvis

object NativeBridge {
    init {
        runCatching { System.loadLibrary("jarvis_native") }
    }

    external fun backendName(): String

    fun generate(modelPath: String, prompt: String): String? {
        if (modelPath.endsWith(".safetensors", true)) {
            return "This Safetensors checkpoint needs conversion to GGUF before Android inference. " +
                "Choose a Qwen GGUF quantization from Hugging Face, or convert it with llama.cpp."
        }
        return runCatching { nativeGenerate(modelPath, prompt) }.getOrNull()
    }

    private external fun nativeGenerate(modelPath: String, prompt: String): String
}
