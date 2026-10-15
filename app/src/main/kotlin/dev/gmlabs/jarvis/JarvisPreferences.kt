package dev.gmlabs.jarvis

import android.content.Context

class JarvisPreferences(context: Context) {
    private val values = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var selectedModelId: String
        get() = values.getString("selected_model", ModelCatalog.models.first().id)!!
        set(value) = values.edit().putString("selected_model", value).apply()

    var importedModelPath: String
        get() = values.getString("imported_model_path", "")!!
        set(value) = values.edit().putString("imported_model_path", value).apply()

    var t2Endpoint: String
        get() = values.getString("t2_endpoint", "")!!
        set(value) = values.edit().putString("t2_endpoint", value).apply()

    var t2Provider: String
        get() = values.getString("t2_provider", "Ollama")!!
        set(value) = values.edit().putString("t2_provider", value).apply()

    var t2ApiKey: String
        get() = values.getString("t2_api_key", "")!!
        set(value) = values.edit().putString("t2_api_key", value).apply()

    var t3Provider: String
        get() = values.getString("t3_provider", "OpenAI")!!
        set(value) = values.edit().putString("t3_provider", value).apply()

    var t3Enabled: Boolean
        get() = values.getBoolean("t3_enabled", false)
        set(value) = values.edit().putBoolean("t3_enabled", value).apply()

    var privacyMode: Boolean
        get() = values.getBoolean("privacy_mode", true)
        set(value) = values.edit().putBoolean("privacy_mode", value).apply()

    var dryRun: Boolean
        get() = values.getBoolean("dry_run", false)
        set(value) = values.edit().putBoolean("dry_run", value).apply()

    var t2Model: String
        get() = values.getString("t2_model", "llama3.2")!!
        set(value) = values.edit().putString("t2_model", value).apply()

    var t3Model: String
        get() = values.getString("t3_model", "gpt-4o-mini")!!
        set(value) = values.edit().putString("t3_model", value).apply()

    var t3BaseUrl: String
        get() = values.getString("t3_base_url", "")!!
        set(value) = values.edit().putString("t3_base_url", value).apply()

    var openAiApiKey: String
        get() = values.getString("openai_api_key", "")!!
        set(value) = values.edit().putString("openai_api_key", value).apply()

    var googleApiKey: String
        get() = values.getString("google_api_key", "")!!
        set(value) = values.edit().putString("google_api_key", value).apply()

    var claudeApiKey: String
        get() = values.getString("claude_api_key", "")!!
        set(value) = values.edit().putString("claude_api_key", value).apply()
}
