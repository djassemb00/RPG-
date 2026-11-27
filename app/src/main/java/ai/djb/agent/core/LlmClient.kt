package ai.djb.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LlmException(message: String) : Exception(message)

data class LlmResponse(
    val content: String,
    val toolCalls: List<ToolCall>,
    val providerLabel: String = "",
)

/** The attempt to reach one provider didn't work; the router may try the next. */
private class AttemptFailed(override val message: String, val retryable: Boolean) : Exception(message)

/**
 * Routes chat calls across an ordered chain of providers.
 *
 * Why: several providers each give a small free tier. Walking the chain on 429/5xx
 * turns those separate quotas into one usable budget — legally, with the user's own
 * keys. Auth/quota failures are remembered so a dead key isn't retried every turn.
 */
class LlmClient(
    private val settings: SettingsStore,
    private val registry: ProviderRegistry,
    private val bus: EventBus,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Ordered, usable chain. Falls back to legacy single-key settings if empty. */
    private fun chain(): List<ProviderSlot> {
        val slots = registry.load().filter { it.enabled && it.configured }
        if (slots.isNotEmpty()) return slots
        if (settings.isConfigured) {
            return listOf(
                ProviderSlot(
                    presetId = "legacy",
                    label = "المزوّد الافتراضي",
                    baseUrl = settings.apiBaseUrl,
                    model = settings.model,
                    key = settings.apiKey,
                )
            )
        }
        return emptyList()
    }

    fun hasUsableProvider(): Boolean = chain().isNotEmpty()

    fun describeChain(): String {
        val c = chain()
        return if (c.isEmpty()) "لا مزوّد مضبوط" else c.joinToString(" → ") { it.label }
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val chain = chain()
        if (chain.isEmpty()) {
            throw LlmException("لا يوجد مزوّد مضبوط. افتح الإعدادات ← المزوّدون وأضف مفتاحًا.")
        }

        val failures = ArrayList<String>()

        for ((index, slot) in chain.withIndex()) {
            try {
                val response = callOnce(slot, messages, tools)
                if (slot.presetId != "legacy") {
                    registry.recordOutcome(slot.presetId, ok = true)
                }
                if (index > 0) {
                    bus.emit(AgentEvent.Log("تم التبديل إلى ${slot.label} بعد فشل المزوّد السابق."))
                }
                return@withContext response.copy(providerLabel = slot.label)
            } catch (e: AttemptFailed) {
                failures += "${slot.label}: ${e.message}"
                if (slot.presetId != "legacy") {
                    registry.recordOutcome(slot.presetId, ok = false, error = e.message)
                }
                // Auth errors won't fix themselves this turn; move on immediately.
                val note = if (e.retryable) {
                    "المزوّد ${slot.label} غير متاح مؤقتًا (${e.message}). أجرّب التالي…"
                } else {
                    "فشل ${slot.label}: ${e.message}. أجرّب المزوّد التالي…"
                }
                bus.emit(AgentEvent.Log(note, LogLevel.WARN))
            }
        }

        throw LlmException(
            "فشلت كل المزوّدات (${chain.size}):\n" + failures.joinToString("\n") { "• $it" }
        )
    }

    /**
     * Asks the provider what it actually serves (GET /models).
     *
     * This is the fix for "model not found": instead of trusting a hard-coded id
     * that may have been retired, we read the provider's live catalog and let the
     * user pick from it.
     */
    suspend fun listModels(slot: ProviderSlot): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = slot.baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$base/models")
                .addHeader("Authorization", "Bearer ${slot.key}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val (code, text) = try {
                http.newCall(request).execute().use { r -> r.code to r.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw LlmException("تعذّر الوصول لقائمة الموديلات: ${e.message}")
            }

            if (code !in 200..299) {
                throw LlmException("HTTP $code عند جلب الموديلات: ${text.errorSnippet()}")
            }

            val root = JSONObject(text)
            val arr = root.optJSONArray("data")
                ?: root.optJSONArray("models")
                ?: throw LlmException("استجابة غير متوقعة من /models")

            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { obj.optString("name") }
                if (id.isNotBlank()) ids.add(id)
            }
            ids.distinct().sorted()
        }
    }

    /**
     * Tries a list of candidate ids and returns the first that answers.
     * Used when a preset's default model has been retired by the provider.
     */
    suspend fun resolveWorkingModel(slot: ProviderSlot, candidates: List<String>): String? =
        withContext(Dispatchers.IO) {
            for (candidate in candidates) {
                val ok = try {
                    callOnce(
                        slot.copy(model = candidate),
                        listOf(ChatMessage.user("hi")),
                        emptyList(),
                    )
                    true
                } catch (e: AttemptFailed) {
                    // Auth problems aren't model-specific: stop early.
                    if (!e.retryable && e.message.contains("غير صالح")) return@withContext null
                    false
                }
                if (ok) return@withContext candidate
            }
            null
        }

    /** Reachability probe used by the "test" button in settings. */
    suspend fun testProvider(slot: ProviderSlot): String = withContext(Dispatchers.IO) {
        try {
            val response = callOnce(
                slot,
                listOf(
                    ChatMessage.system("You are a connectivity probe."),
                    ChatMessage.user("Reply with the single word: ok"),
                ),
                emptyList(),
            )
            if (slot.presetId != "legacy") registry.recordOutcome(slot.presetId, ok = true)
            val text = response.content.take(80).ifBlank { "(رد فارغ)" }
            "ناجح — ${slot.label} / ${slot.model} ردّ: $text"
        } catch (e: AttemptFailed) {
            if (slot.presetId != "legacy") {
                registry.recordOutcome(slot.presetId, ok = false, error = e.message)
            }
            "فشل — ${slot.label}: ${e.message}"
        }
    }

    private fun callOnce(
        slot: ProviderSlot,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        val body = JSONObject().apply {
            put("model", slot.model)
            put("temperature", settings.temperature)
            put("messages", JSONArray().apply { messages.forEach { put(it.toOpenAi()) } })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply { tools.forEach { put(it.toOpenAi()) } })
                put("tool_choice", "auto")
                // Parallel calls multiply malformed arguments; keep it to one at a time.
                put("parallel_tool_calls", false)
            }
        }

        val base = slot.baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer ${slot.key}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            // OpenRouter asks for attribution headers; harmless for other providers.
            .addHeader("HTTP-Referer", "https://github.com/djassemb00/RPG-")
            .addHeader("X-Title", "djb ai")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val (code, text) = try {
            http.newCall(request).execute().use { response ->
                (response.code to response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw AttemptFailed("تعذّر الوصول (${e.message})", retryable = true)
        }

        if (code !in 200..299) {
            val snippet = text.errorSnippet()
            val hint = when (code) {
                400 -> buildString {
                    append("طلب مرفوض (400)")
                    if (snippet.contains("schema", ignoreCase = true)) {
                        append(" — مخطط إحدى الأدوات غير صالح عند هذا المزوّد")
                    } else if (snippet.contains("tool", ignoreCase = true)) {
                        append(" — المزوّد رفض تعريف الأدوات (قد لا يدعم tool calling)")
                    } else if (snippet.contains("model", ignoreCase = true)) {
                        append(" — اسم الموديل غير مقبول")
                    }
                }
                401, 403 -> "المفتاح غير صالح أو غير مصرّح له"
                404 -> "الموديل أو المسار غير موجود — تحقق من Base URL واسم الموديل"
                429 -> "تجاوز حد الطلبات"
                in 500..599 -> "خطأ مؤقت عند المزوّد"
                else -> "HTTP $code"
            }
            throw AttemptFailed("$hint: $snippet", retryable = code == 429 || code >= 500)
        }

        return parse(text)
    }

    private fun parse(raw: String): LlmResponse {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw AttemptFailed("رد غير صالح من المزوّد", retryable = false) }

        root.optJSONObject("error")?.let { err ->
            throw AttemptFailed(err.optString("message", err.toString()).take(200), retryable = false)
        }

        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AttemptFailed("لا يوجد رد في الاستجابة", retryable = false)

        val message = choice.optJSONObject("message") ?: JSONObject()
        val content = message.optString("content").takeIf { it.isNotBlank() && it != "null" }.orEmpty()

        val calls = ArrayList<ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val fn = obj.optJSONObject("function") ?: continue
                val argsRaw = fn.optString("arguments", "{}").ifBlank { "{}" }
                val args = runCatching { JSONObject(argsRaw) }
                    .getOrElse { JSONObject().put("_parse_error", argsRaw.take(500)) }
                calls.add(
                    ToolCall(
                        id = obj.optString("id", "call_$i"),
                        name = fn.optString("name"),
                        arguments = args,
                        rawArguments = argsRaw,
                    )
                )
            }
        }

        return LlmResponse(content, calls)
    }
}
