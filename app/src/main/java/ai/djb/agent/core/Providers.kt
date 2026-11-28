package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Which part of an agent turn a provider should serve.
 *
 * Planning is cheap (short prompt, one small decision); acting is expensive (long
 * history, tool schemas, multi-step). Letting those use different models is the
 * single biggest cost lever in an agent loop.
 */
enum class Role {
    /** Preferred for the first model call of a run: intent + short plan. */
    PLAN,

    /** Preferred for tool-calling turns: the expensive part. */
    ACT,

    /** Usable for anything (default, backwards compatible). */
    ANY,
    ;

    fun label(): String = when (this) {
        PLAN -> "تخطيط"
        ACT -> "تنفيذ"
        ANY -> "عام"
    }
}

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
    /** Which phase of a run this provider is preferred for. */
    val role: Role = Role.ANY,
    /** Optional lighter/cheaper model used during planning, falling back to `model`. */
    val planModel: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && key.isNotBlank()

    /** The model to use for a given phase. */
    fun modelFor(phase: Role): String = when (phase) {
        Role.PLAN -> planModel.ifBlank { model }
        else -> model
    }

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
        put("role", role.name)
        put("plan_model", planModel)
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
            role = runCatching { Role.valueOf(obj.optString("role", "ANY")) }.getOrDefault(Role.ANY),
            planModel = obj.optString("plan_model"),
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
         *
         * Model catalogs churn fast (Groq retired Llama 3.3 for free tiers in Aug 2026,
         * GitHub Models was retired entirely in Jul 2026). The default model here is
         * only a starting guess: the app fetches /models from the provider on save and
         * the user can pick from what the provider actually serves right now.
         */
        val ALL: List<ProviderPreset> = listOf(
            ProviderPreset(
                "groq", "Groq", "https://api.groq.com/openai/v1",
                "openai/gpt-oss-120b",
                "طبقة مجانية: ~30 طلب/دقيقة · 1000/يوم", "https://console.groq.com/keys",
            ),
            ProviderPreset(
                "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                "gemini-2.0-flash",
                "طبقة مجانية بحدود يومية", "https://aistudio.google.com/apikey",
            ),
            ProviderPreset(
                "cerebras", "Cerebras", "https://api.cerebras.ai/v1",
                "gpt-oss-120b",
                "طبقة مجانية سريعة جدًا", "https://cloud.cerebras.ai",
            ),
            ProviderPreset(
                "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                "openai/gpt-oss-120b:free",
                "موديلات :free عديدة (تحقق من القائمة الحية)", "https://openrouter.ai/keys",
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

    fun setEnabled(presetId: String, enabled: Boolean): List<ProviderSlot> {
        save(load().map { if (it.presetId == presetId) it.copy(enabled = enabled) else it })
        return load()
    }

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
     * Ordered chain for a phase. Providers tagged for that phase come first, then
     * `ANY` providers, then everything else as a last-resort fallback — so a routing
     * preference never becomes a single point of failure.
     */
    fun chainFor(phase: Role): List<ProviderSlot> {
        val usable = load().filter { it.enabled && it.configured }
        val preferred = usable.filter { it.role == phase }
        val generic = usable.filter { it.role == Role.ANY }
        val other = usable.filter { it.role != phase && it.role != Role.ANY }
        return (preferred + generic + other).distinctBy { it.presetId }
    }

    fun setRole(presetId: String, role: Role): List<ProviderSlot> {
        save(load().map { if (it.presetId == presetId) it.copy(role = role) else it })
        return load()
    }

    fun setPlanModel(presetId: String, model: String): List<ProviderSlot> {
        save(load().map { if (it.presetId == presetId) it.copy(planModel = model) else it })
        return load()
    }

    /** Human-readable explanation of how a turn will be routed right now. */
    fun routingSummary(): String {
        val plan = chainFor(Role.PLAN).firstOrNull()
        val act = chainFor(Role.ACT).firstOrNull()
        if (plan == null && act == null) return "لا مزوّد مضبوط"
        val planText = plan?.let { "${it.label} / ${it.modelFor(Role.PLAN)}" } ?: "—"
        val actText = act?.let { "${it.label} / ${it.modelFor(Role.ACT)}" } ?: "—"
        return if (planText == actText) "كل المراحل: $actText" else "تخطيط: $planText · تنفيذ: $actText"
    }

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
