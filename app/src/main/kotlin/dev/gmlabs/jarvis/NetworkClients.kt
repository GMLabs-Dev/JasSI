package dev.gmlabs.jarvis

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Completion(val text: String, val estimatedCostCents: Int = 0)

object JsonHttp {
    fun post(
        url: String,
        body: JSONObject,
        headers: Map<String, String?> = emptyMap(),
        timeoutMs: Int = 20_000
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
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: $response")
        return JSONObject(response)
    }
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
            val base = endpoint.trimEnd('/')
                .removeSuffix("/v1")
                .removeSuffix("/api")
            val url = if (provider.equals("LM Studio", ignoreCase = true)) {
                "$base/v1/chat/completions"
            } else {
                "$base/api/chat"
            }
            val body = if (provider.equals("LM Studio", ignoreCase = true)) {
                JSONObject()
                    .put("model", model)
                    .put("messages", org.json.JSONArray().put(
                        JSONObject().put("role", "user").put("content", context.input)
                    ))
                    .put("stream", false)
            } else {
                JSONObject()
                    .put("model", model)
                    .put("messages", org.json.JSONArray().put(
                        JSONObject().put("role", "user").put("content", context.input)
                    ))
                    .put("stream", false)
            }
            val response = JsonHttp.post(
                url,
                body,
                headers = mapOf("Authorization" to apiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" })
            )
            val text = if (provider.equals("LM Studio", ignoreCase = true)) {
                response.optJSONObject("choices")?.optString("message")
                    ?: response.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                    ?: response.optString("response")
            } else {
                response.optJSONObject("message")?.optString("content")
                    ?: response.optString("response")
            }
            if (text.isBlank()) error("The server returned no response text.")
            RouteResult(context.requestId, tier, text, latencyMs = elapsed(started))
        } catch (error: Exception) {
            throw TierUnavailableException("T2 unavailable: ${error.message}")
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
            val response = when (provider.lowercase()) {
                "openai" -> JsonHttp.post(
                    "https://api.openai.com/v1/chat/completions",
                    chatBody(model, context.input),
                    mapOf("Authorization" to "Bearer $apiKey")
                )
                "claude" -> JsonHttp.post(
                    "https://api.anthropic.com/v1/messages",
                    JSONObject()
                        .put("model", model)
                        .put("max_tokens", 1024)
                        .put("messages", org.json.JSONArray().put(
                            JSONObject().put("role", "user").put("content", context.input)
                        )),
                    mapOf(
                        "x-api-key" to apiKey,
                        "anthropic-version" to "2023-06-01"
                    )
                )
                "google" -> JsonHttp.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "${URLEncoder.encode(model, "UTF-8")}:generateContent?key=$apiKey",
                    JSONObject().put(
                        "contents", org.json.JSONArray().put(
                            JSONObject().put(
                                "parts", org.json.JSONArray().put(
                                    JSONObject().put("text", context.input)
                                )
                            )
                        )
                    )
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
            throw TierUnavailableException("T3 unavailable: ${error.message}")
        }
    }

    private fun chatBody(model: String, input: String) = JSONObject()
        .put("model", model)
        .put("messages", org.json.JSONArray().put(
            JSONObject().put("role", "user").put("content", input)
        ))
        .put("max_tokens", 1024)
}

private fun elapsed(started: Long) = (System.currentTimeMillis() - started).coerceAtLeast(0)
