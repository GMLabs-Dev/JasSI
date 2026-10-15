package dev.gmlabs.jarvis

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Completion(val text: String, val estimatedCostCents: Int = 0)

object JsonHttp {
    fun post(url: String, body: JSONObject, timeoutMs: Int = 20_000): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: $response")
        return JSONObject(response)
    }
}

class OllamaProcessor(
    private val endpoint: String,
    private val model: String
) : TierProcessor {
    override val tier = Tier.T2
    override fun canHandle(input: String) = input.isNotBlank()
    override fun process(context: RequestContext): RouteResult {
        val started = System.currentTimeMillis()
        return try {
            val response = JsonHttp.post(
                "${endpoint.trimEnd('/')}/api/generate",
                JSONObject().put("model", model).put("prompt", context.input).put("stream", false)
            )
            RouteResult(context.requestId, tier, response.optString("response"), latencyMs = elapsed(started))
        } catch (error: Exception) {
            throw TierUnavailableException("T2 unavailable: ${error.message}")
        }
    }
}

class GatewayProcessor(
    private val endpoint: String,
    private val provider: String,
    private val model: String,
    private val budgetCents: Int
) : TierProcessor {
    override val tier = Tier.T3
    override fun canHandle(input: String) = input.isNotBlank()
    override fun process(context: RequestContext): RouteResult {
        val started = System.currentTimeMillis()
        return try {
            val response = JsonHttp.post(
                "${endpoint.trimEnd('/')}/v1/complete",
                JSONObject()
                    .put("prompt", context.input)
                    .put("provider", provider.lowercase())
                    .put("model", model)
                    .put("max_cost_cents", budgetCents)
                    .put("privacy_mode", false)
            )
            RouteResult(
                context.requestId, tier, response.optString("text"),
                latencyMs = elapsed(started),
                estimatedCostCents = response.optInt("estimated_cost_cents")
            )
        } catch (error: Exception) {
            throw TierUnavailableException("T3 unavailable: ${error.message}")
        }
    }
}

private fun elapsed(started: Long) = (System.currentTimeMillis() - started).coerceAtLeast(0)
