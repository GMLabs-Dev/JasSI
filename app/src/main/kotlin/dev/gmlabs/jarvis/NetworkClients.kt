package dev.gmlabs.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object JsonHttp {
    fun get(
        url: String,
        headers: Map<String, String?> = emptyMap(),
        timeoutMs: Int = 10_000
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        headers.forEach { (name, value) ->
            if (!value.isNullOrBlank()) connection.setRequestProperty(name, value)
        }
        return read(connection)
    }

    fun post(
        url: String,
        body: JSONObject,
        headers: Map<String, String?> = emptyMap(),
        timeoutMs: Int = 10_000
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (name, value) ->
            if (!value.isNullOrBlank()) connection.setRequestProperty(name, value)
        }
        return try {
            connection.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(connection: HttpURLConnection): JSONObject {
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("error") }
                    .getOrNull().orEmpty()
                error("HTTP $status${if (detail.isBlank()) "" else ": $detail"}")
            }
            if (response.isBlank()) error("The server returned an empty response.")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}

private fun localBase(endpoint: String): String {
    var base = endpoint.trim().trimEnd('/')
    if (!base.contains("://")) base = "http://$base"
    val knownPaths = listOf(
        "/v1/chat/completions",
        "/v1/models",
        "/api/chat",
        "/api/tags",
        "/v1",
        "/api"
    )
    while (knownPaths.any { base.endsWith(it) }) {
        knownPaths.firstOrNull { base.endsWith(it) }?.let { suffix ->
            base = base.removeSuffix(suffix).trimEnd('/')
        }
    }
    return base
}

private fun bearer(apiKey: String): String? =
    apiKey.trim().takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it"
    }

class OllamaProcessor(
    private val endpoint: String,
    private val model: String,
    private val provider: String,
    private val apiKey: String
) : TierProcessor {
    override val tier = Tier.T2
    override fun canHandle(input: String) = input.isNotBlank()

    override fun process(context: RequestContext): RouteResult {
        val started = System.currentTimeMillis()
        return try {
            val base = localBase(endpoint)
            val lmStudio = provider.equals("LM Studio", ignoreCase = true)
            require(model.isNotBlank()) { "Choose a model in the T2 settings." }
            val body = JSONObject()
                .put("model", model.trim())
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", context.input)
                ))
                .put("stream", false)
            val response = JsonHttp.post(
                if (lmStudio) "$base/v1/chat/completions" else "$base/api/chat",
                body,
                headers = mapOf("Authorization" to bearer(apiKey))
            )
            val text = if (lmStudio) {
                response.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
            } else {
                response.optJSONObject("message")?.optString("content").orEmpty()
            }
            if (text.isBlank()) error("The server returned no response text.")
            RouteResult(context.requestId, tier, text, latencyMs = elapsed(started))
        } catch (error: Exception) {
            throw TierUnavailableException("T2 unavailable: ${error.message ?: "connection failed"}")
        }
    }
}

class CloudProcessor(
    private val provider: String,
    private val model: String,
    private val apiKey: String
) : TierProcessor {
    override val tier = Tier.T3
    override fun canHandle(input: String) = input.isNotBlank()

    override fun process(context: RequestContext): RouteResult {
        val started = System.currentTimeMillis()
        return try {
            require(model.isNotBlank()) { "Choose a cloud model in the T3 settings." }
            val response = when (provider.lowercase()) {
                "openai" -> JsonHttp.post(
                    "https://api.openai.com/v1/chat/completions",
                    chatBody(model, context.input),
                    mapOf("Authorization" to bearer(apiKey))
                )
                "claude" -> JsonHttp.post(
                    "https://api.anthropic.com/v1/messages",
                    JSONObject()
                        .put("model", model)
                        .put("max_tokens", 1024)
                        .put("messages", JSONArray().put(
                            JSONObject().put("role", "user").put("content", context.input)
                        )),
                    mapOf(
                        "x-api-key" to apiKey,
                        "anthropic-version" to "2023-06-01"
                    )
                )
                "google" -> JsonHttp.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "${URLEncoder.encode(model.removePrefix("models/"), "UTF-8")}:generateContent?key=" +
                        URLEncoder.encode(apiKey, "UTF-8"),
                    JSONObject().put("contents", JSONArray().put(
                        JSONObject().put("role", "user").put("parts", JSONArray().put(
                            JSONObject().put("text", context.input)
                        ))
                    ))
                )
                else -> error("Unknown cloud provider: $provider")
            }
            val text = when (provider.lowercase()) {
                "openai" -> response.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                "claude" -> response.optJSONArray("content")?.optJSONObject(0)?.optString("text")
                else -> response.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optString("text")
            }.orEmpty()
            if (text.isBlank()) error("The provider returned no response text.")
            RouteResult(context.requestId, tier, text, latencyMs = elapsed(started))
        } catch (error: Exception) {
            throw TierUnavailableException("T3 unavailable: ${error.message ?: "request failed"}")
        }
    }

    private fun chatBody(model: String, input: String) = JSONObject()
        .put("model", model)
        .put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", input)
        ))
        .put("max_tokens", 1024)
}

object ProviderDiscovery {
    fun local(provider: String, endpoint: String, apiKey: String): String {
        val base = localBase(endpoint)
        val headers = mapOf("Authorization" to bearer(apiKey))
        return if (provider.equals("LM Studio", ignoreCase = true)) {
            val response = JsonHttp.get("$base/v1/models", headers)
            val models = response.optJSONArray("data")
                ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("id") } }
                .orEmpty()
            "LM Studio is reachable. Models: ${models.ifEmpty { listOf("none reported") }.joinToString()}"
        } else {
            val response = JsonHttp.get("$base/api/tags", headers)
            val models = response.optJSONArray("models")
                ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") } }
                .orEmpty()
            "Ollama is reachable. Models: ${models.ifEmpty { listOf("none reported") }.joinToString()}"
        }
    }

    fun cloud(provider: String, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Enter an API key first." }
        return when (provider.lowercase()) {
            "openai" -> {
                val response = JsonHttp.get(
                    "https://api.openai.com/v1/models",
                    mapOf("Authorization" to bearer(apiKey))
                )
                "OpenAI key accepted. ${response.optJSONArray("data")?.length() ?: 0} models are visible."
            }
            "google" -> {
                val response = JsonHttp.get(
                    "https://generativelanguage.googleapis.com/v1beta/models?key=" +
                        URLEncoder.encode(apiKey, "UTF-8")
                )
                "Google AI key accepted. ${response.optJSONArray("models")?.length() ?: 0} models are visible."
            }
            "claude" -> "Claude does not expose a no-cost key-validation endpoint. The key will be checked on the next request."
            else -> error("Unknown cloud provider: $provider")
        }
    }
}

private fun elapsed(started: Long) = (System.currentTimeMillis() - started).coerceAtLeast(0)
