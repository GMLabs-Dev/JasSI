package dev.gmlabs.jarvis

import java.io.File
import java.io.RandomAccessFile

object NativeBridge {
    init {
        runCatching { System.loadLibrary("jarvis_native") }
    }

    private external fun backendName(): String

    fun backendDescription(): String =
        runCatching { backendName() }.getOrElse { "CPU fallback (native backend unavailable)" }

    fun generate(modelPath: String, prompt: String): String? {
        if (modelPath.endsWith(".safetensors", true)) {
            return "This Safetensors checkpoint needs conversion to GGUF before Android inference. " +
                "Choose a Qwen GGUF quantization from Hugging Face, or convert it with llama.cpp."
        }
        val file = File(modelPath)
        if (!file.isFile) return "GGUF file was not found: $modelPath"
        if (file.length() < 1024 * 1024) {
            return "The selected GGUF file is incomplete or too small (${file.length()} bytes)."
        }
        val magic = runCatching {
            RandomAccessFile(file, "r").use { handle ->
                ByteArray(4).also { handle.readFully(it) }
            }
        }.getOrNull()
        if (magic == null || String(magic, Charsets.US_ASCII) != "GGUF") {
            return "The selected file is not a valid GGUF model. Re-download the .gguf file, not a Hugging Face webpage or Safetensors checkpoint."
        }
        return runCatching { nativeGenerate(modelPath, prompt) }
            .getOrElse { "GGUF inference failed: ${it.message ?: "native error"}" }
    }

    private external fun nativeGenerate(modelPath: String, prompt: String): String
    private external fun nativeTranscribe(modelPath: String, samples: FloatArray): String

    fun transcribe(modelPath: String, samples: FloatArray): String =
        runCatching { nativeTranscribe(modelPath, samples) }
            .getOrElse { "Whisper transcription failed: ${it.message ?: "native error"}" }
}
