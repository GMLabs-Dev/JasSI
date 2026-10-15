package dev.gmlabs.jarvis

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile

class LocalModelManager(private val context: Context) {
    fun modelDirectory(model: LocalModel): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), model.id.replace('/', '_'))

    fun isDownloaded(model: LocalModel): Boolean {
        val file = File(modelDirectory(model), "model.gguf")
        return isValidGguf(file)
    }

    fun isValidGguf(file: File): Boolean {
        if (!file.isFile || file.length() < 1024 * 1024) return false
        return runCatching {
            RandomAccessFile(file, "r").use { handle ->
                ByteArray(4).also { handle.readFully(it) }
            }.contentEquals("GGUF".toByteArray(Charsets.US_ASCII))
        }.getOrDefault(false)
    }

    fun importModel(uri: Uri, modelName: String): File {
        val extension = context.contentResolver.getType(uri)
            ?.takeIf { it.contains("safetensors", true) }
            ?.let { ".safetensors" }
            ?: if (modelName.endsWith(".safetensors")) ".safetensors" else ".gguf"
        val target = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "imported/${modelName.substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")}$extension"
        )
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open selected model file." }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        require(isValidGguf(target)) {
            "The selected file is not a complete GGUF model. Select a .gguf file, not model.safetensors or a browser download page."
        }
        return target
    }

    fun download(model: LocalModel): Long {
        val url = model.ggufUrl
            ?: "https://huggingface.co/${model.id}/resolve/main/model.gguf"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading ${model.name}")
            .setDescription("JasSI local model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "${model.id.replace('/', '_')}/model.gguf"
            )
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}
