package dev.gmlabs.jarvis

import android.os.StatFs
import android.content.Context
import kotlin.math.roundToInt

enum class Accelerator(val label: String) {
    HEXAGON("Hexagon NPU"),
    GPU("GPU delegate"),
    CPU("CPU fallback")
}

data class LocalModel(
    val id: String,
    val name: String,
    val parameters: String,
    val memoryGb: Float,
    val accelerator: Accelerator,
    val description: String,
    val ggufUrl: String? = null
)

data class DeviceProfile(
    val memoryGb: Float,
    val cores: Int,
    val hasHexagon: Boolean,
    val hasGpu: Boolean
) {
    val summary: String
        get() = "${memoryGb.roundToInt()} GB RAM · $cores CPU cores · " +
            if (hasHexagon) "Hexagon backend available" else "CPU/GPU inference"
}

object ModelCatalog {
    val models = listOf(
        LocalModel(
            "Qwen/Qwen2.5-1.5B-Instruct",
            "Qwen 2.5 1.5B",
            "1.5B",
            2f,
            Accelerator.HEXAGON,
            "Fast, compact instruction model for private everyday questions.",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ),
        LocalModel(
            "HuggingFaceTB/SmolLM2-1.7B-Instruct",
            "SmolLM2 1.7B",
            "1.7B",
            2.5f,
            Accelerator.GPU,
            "Lightweight model with a good speed and quality balance."
        ),
        LocalModel(
            "Qwen/Qwen2.5-3B-Instruct",
            "Qwen 2.5 3B",
            "3B",
            4f,
            Accelerator.GPU,
            "Stronger local reasoning for phones with more memory."
        ),
        LocalModel(
            "microsoft/Phi-3.5-mini-instruct",
            "Phi-3.5 Mini",
            "3.8B",
            5f,
            Accelerator.CPU,
            "Best quality option here, recommended only for high-end devices."
        )
    )

    fun profile(context: Context): DeviceProfile {
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        memory.getMemoryInfo(info)
        val ramGb = info.totalMem / 1024f / 1024f / 1024f
        return DeviceProfile(
            memoryGb = ramGb,
            cores = Runtime.getRuntime().availableProcessors(),
            hasHexagon = NativeBridge.backendDescription(context.applicationInfo.nativeLibraryDir)
                .contains("hexagon", ignoreCase = true),
            hasGpu = true
        )
    }

    fun recommendation(context: Context): LocalModel {
        val device = profile(context)
        return models
            .filter { it.memoryGb <= (device.memoryGb * 0.55f).coerceAtLeast(1.5f) }
            .maxByOrNull { it.memoryGb }
            ?: models.first()
    }

    fun storageAvailableGb(context: Context): Long {
        val stats = StatFs(context.filesDir.path)
        return stats.availableBytes / 1024 / 1024 / 1024
    }
}
