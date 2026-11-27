package ai.djb.agent.core

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmException(message: String) : Exception(message)

data class LlmResponse(
    val content: String,
    val toolCalls: List<ToolCall>,
)

/**
 * Speaks the OpenAI-compatible chat-completions dialect, which is supported by
 * OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Ollama and Gemini's compat layer.
 * One client, many providers — the user only swaps the base URL + model.
 */
class LlmClient(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (!settings.isConfigured) {
            throw LlmException("لم يتم ضبط مفتاح الـ API أو الموديل بعد. افتح الإعدادات.")
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("temperature", settings.temperature)
            put("messages", JSONArray().apply { messages.forEach { put(it.toOpenAi()) } })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply { tools.forEach { put(it.toOpenAi()) } })
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url("${settings.apiBaseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val payload = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("HTTP ${response.code}: ${text.take(400)}")
                }
                text
            }
        } catch (e: IOException) {
            throw LlmException("تعذّر الوصول للمزوّد: ${e.message}")
        }

        parse(payload)
    }

    private fun parse(raw: String): LlmResponse {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw LlmException("رد غير صالح من المزوّد.") }

        root.optJSONObject("error")?.let { err ->
            throw LlmException("خطأ المزوّد: ${err.optString("message", err.toString())}")
        }

        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmException("لا يوجد رد في الاستجابة.")

        val message = choice.optJSONObject("message") ?: JSONObject()
        val content = message.optString("content").takeIf { it.isNotBlank() && it != "null" }.orEmpty()

        val calls = ArrayList<ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val fn = obj.optJSONObject("function") ?: continue
                val argsRaw = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsRaw) }
                    .getOrElse { JSONObject().put("_raw", argsRaw) }
                calls.add(
                    ToolCall(
                        id = obj.optString("id", "call_$i"),
                        name = fn.optString("name"),
                        arguments = args,
                    )
                )
            }
        }

        return LlmResponse(content, calls)
    }
}
