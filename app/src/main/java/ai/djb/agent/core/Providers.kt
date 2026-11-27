package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A provider entry the user actually holds a key for.
 *
 * Keys are stored separately per provider so rotating one never touches the others,
 * and `key` is never written to logs or shown in the UI.
 */
data class ProviderSlot(
    val presetId: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val key: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val lastError: String? = null,
    val lastOkAt: Long = 0,
    val lastFailAt: Long = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && key.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("preset_id", presetId)
        put("label", label)
        put("base_url", baseUrl)
        put("model", model)
        put("key", key)
        put("enabled", enabled)
        put("priority", priority)
        lastError?.let { put("last_error", it) }
        put("last_ok_at", lastOkAt)
        put("last_fail_at", lastFailAt)
        put("success_count", successCount)
        put("fail_count", failCount)
    }

    companion object {
        fun fromJson(obj: JSONObject) = ProviderSlot(
            presetId = obj.optString("preset_id"),
            label = obj.optString("label"),
            baseUrl = obj.optString("base_url"),
            model = obj.optString("model"),
            key = obj.optString("key"),
            enabled = obj.optBoolean("enabled", true),
            priority = obj.optInt("priority"),
            lastError = obj.optString("last_error").takeIf { it.isNotBlank() },
            lastOkAt = obj.optLong("last_ok_at"),
            lastFailAt = obj.optLong("last_fail_at"),
            successCount = obj.optInt("success_count"),
            failCount = obj.optInt("fail_count"),
        )
    }
}

/** A known-good provider with a free tier, taken from the public catalogues. */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val freeTier: String,
    val signupUrl: String,
) {
    companion object {
        /**
         * Curated from cheahjs/free-llm-api-resources and similar directories.
         * These are the provider's own free tiers — you create the key yourself.
         * Nothing here is a shared or leaked key.
         */
        val ALL: List<ProviderPreset> = listOf(
            ProviderPreset(
                "groq", "Groq", "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile",
                "طبقة مجانية سخية (~30 طلب/دقيقة)", "https://console.groq.com/keys",
            ),
            ProviderPreset(
                "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                "gemini-2.0-flash",
                "طبقة مجانية بحدود يومية", "https://aistudio.google.com/apikey",
            ),
            ProviderPreset(
                "cerebras", "Cerebras", "https://api.cerebras.ai/v1",
                "llama-3.3-70b",
                "طبقة مجانية سريعة جدًا", "https://cloud.cerebras.ai",
            ),
            ProviderPreset(
                "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                "google/gemini-2.0-flash-exp:free",
                "موديلات :free عديدة", "https://openrouter.ai/keys",
            ),
            ProviderPreset(
                "mistral", "Mistral", "https://api.mistral.ai/v1",
                "mistral-small-latest",
                "طبقة مجانية (La Plateforme)", "https://console.mistral.ai/api-keys",
            ),
            ProviderPreset(
                "nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1",
                "meta/llama-3.3-70b-instruct",
                "طبقة مجانية واسعة", "https://build.nvidia.com",
            ),
            ProviderPreset(
                "github", "GitHub Models", "https://models.inference.ai.azure.com",
                "gpt-4o-mini",
                "مجاني بحساب GitHub", "https://github.com/settings/tokens",
            ),
            ProviderPreset(
                "openai", "OpenAI", "https://api.openai.com/v1",
                "gpt-4o-mini",
                "مدفوع (بدون طبقة مجانية)", "https://platform.openai.com/api-keys",
            ),
            ProviderPreset(
                "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
                "deepseek-chat",
                "رخيص جدًا", "https://platform.deepseek.com/api_keys",
            ),
            ProviderPreset(
                "ollama", "Ollama (محلي/LAN)", "http://192.168.1.10:11434/v1",
                "qwen2.5:7b",
                "محلي بالكامل", "https://ollama.com",
            ),
        )

        fun byId(id: String): ProviderPreset? = ALL.firstOrNull { it.id == id }
    }
}

/**
 * Owns the ordered provider chain and persists it.
 *
 * Order = priority. The router walks it top-down and skips anything unusable,
 * which is what turns several small free tiers into one usable budget.
 */
class ProviderRegistry(private val store: JsonStore) {

    private val file = "providers.json"

    fun load(): List<ProviderSlot> {
        val arr = store.readArray(file)
        val slots = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(ProviderSlot::fromJson) }
        return slots.sortedBy { it.priority }
    }

    fun save(slots: List<ProviderSlot>) {
        val normalized = slots.mapIndexed { index, slot -> slot.copy(priority = index) }
        store.writeArray(file, JSONArray().apply { normalized.forEach { put(it.toJson()) } })
    }

    fun upsert(slot: ProviderSlot): List<ProviderSlot> {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.presetId == slot.presetId }
        if (idx >= 0) current[idx] = slot else current.add(slot)
        save(current)
        return load()
    }

    fun remove(presetId: String): List<ProviderSlot> {
        save(load().filterNot { it.presetId == presetId })
        return load()
    }

    fun setEnabled(presetId: String, enabled: Boolean): List<ProviderSlot> =
        save(load().map { if (it.presetId == presetId) it.copy(enabled = enabled) else it })

    fun move(presetId: String, up: Boolean): List<ProviderSlot> {
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.presetId == presetId }
        if (i < 0) return list
        val j = if (up) i - 1 else i + 1
        if (j !in list.indices) return list
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        save(list)
        return load()
    }

    fun recordOutcome(presetId: String, ok: Boolean, error: String? = null): List<ProviderSlot> {
        val now = System.currentTimeMillis()
        val updated = load().map { slot ->
            if (slot.presetId != presetId) slot
            else if (ok) slot.copy(
                successCount = slot.successCount + 1,
                lastOkAt = now,
                lastError = null,
            )
            else slot.copy(
                failCount = slot.failCount + 1,
                lastFailAt = now,
                lastError = error?.take(200),
            )
        }
        save(updated)
        return updated
    }

    /** Convenience: the first slot that is enabled and fully configured. */
    fun firstUsable(): ProviderSlot? = load().firstOrNull { it.enabled && it.configured }

    /**
     * One-time bridge for settings.json written by v0.1.0, so upgrading users
     * don't lose the single provider they already configured.
     */
    fun migrateLegacySettings(settings: SettingsStore) {
        if (load().isNotEmpty()) return
        if (!settings.isConfigured) return
        val base = settings.apiBaseUrl
        val preset = ProviderPreset.ALL.firstOrNull { it.baseUrl.trimEnd('/') == base.trimEnd('/') }
        upsert(
            ProviderSlot(
                presetId = preset?.id ?: "custom",
                label = preset?.label ?: "مزوّد مخصص",
                baseUrl = base,
                model = settings.model,
                key = settings.apiKey,
            )
        )
    }
}

/** Coerce messy provider replies into a readable one-liner for the UI. */
internal fun String.errorSnippet(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return "خطأ غير معروف"
    return runCatching {
        val obj = JSONTokener(trimmed).nextValue()
        when (obj) {
            is JSONObject -> obj.optJSONObject("error")?.optString("message")
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("error").takeIf { it.isNotBlank() }
                ?: trimmed.take(200)
            else -> trimmed.take(200)
        }
    }.getOrElse { trimmed.take(200) }
}
