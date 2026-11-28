package ai.djb.agent.core

import org.json.JSONObject

enum class Autonomy {
    /** Every MEDIUM/HIGH risk action waits for a tap. */
    ASK,

    /** MEDIUM actions run freely, HIGH still asks. */
    BALANCED,

    /** Everything runs; only use while watching the screen. */
    FULL,
}

/**
 * User-facing configuration. The API key never leaves the app's private storage
 * and is excluded from logs and from the model's own view.
 */
class SettingsStore(private val store: JsonStore) {

    private val file = "settings.json"

    private fun json(): JSONObject = store.readObject(file)

    var apiBaseUrl: String
        get() = json().optString("api_base_url", "https://api.openai.com/v1")
        set(value) = put("api_base_url", value.trim().trimEnd('/'))

    var apiKey: String
        get() = json().optString("api_key")
        set(value) = put("api_key", value.trim())

    var model: String
        get() = json().optString("model", "gpt-4o-mini")
        set(value) = put("model", value.trim())

    var maxSteps: Int
        get() = json().optInt("max_steps", 12)
        set(value) = put("max_steps", value.coerceIn(1, 60))

    var temperature: Double
        get() = json().optDouble("temperature", 0.3)
        set(value) = put("temperature", value.coerceIn(0.0, 1.5))

    var autonomy: Autonomy
        get() = runCatching { Autonomy.valueOf(json().optString("autonomy", "ASK")) }
            .getOrDefault(Autonomy.ASK)
        set(value) = put("autonomy", value.name)

    var selfImprove: Boolean
        get() = json().optBoolean("self_improve", true)
        set(value) = put("self_improve", value)

    var agentName: String
        get() = json().optString("agent_name", "djb")
        set(value) = put("agent_name", value.trim().ifBlank { "djb" })

    /** Log which model served each phase — useful when tuning cost. */
    var showRouting: Boolean
        get() = json().optBoolean("show_routing", true)
        set(value) = put("show_routing", value)

    /** Allow sending screenshots to the model (costs image tokens). */
    var visionEnabled: Boolean
        get() = json().optBoolean("vision_enabled", true)
        set(value) = put("vision_enabled", value)

    /** Distil a lesson from each successful run without being asked. */
    var autoLearn: Boolean
        get() = json().optBoolean("auto_learn", true)
        set(value) = put("auto_learn", value)

    /** Cloud worker base URL, e.g. https://djb-ai-worker.vercel.app */
    var cloudUrl: String
        get() = json().optString("cloud_url")
        set(value) = put("cloud_url", value.trim().trimEnd('/'))

    /** Shared secret for the worker; never logged, never exported. */
    var cloudToken: String
        get() = json().optString("cloud_token")
        set(value) = put("cloud_token", value.trim())

    /** Stable per-install id so several devices can share one cloud brain. */
    var cloudClientId: String
        get() = json().optString("cloud_client").ifBlank { "primary" }
        set(value) = put("cloud_client", value.trim().ifBlank { "primary" })

    /** Fine-grained GitHub token with Actions + Contents write. Never exported. */
    var githubToken: String
        get() = json().optString("github_token")
        set(value) = put("github_token", value.trim())

    /** owner/repo that holds this app's source. */
    var githubRepo: String
        get() = json().optString("github_repo")
        set(value) = put("github_repo", value.trim().removePrefix("https://github.com/").trimEnd('/'))

    var githubBranch: String
        get() = json().optString("github_branch", "main")
        set(value) = put("github_branch", value.trim().ifBlank { "main" })

    var githubWorkflow: String
        get() = json().optString("github_workflow", "self-build.yml")
        set(value) = put("github_workflow", value.trim().ifBlank { "self-build.yml" })

    var extraInstruction: String
        get() = json().optString("extra_instruction")
        set(value) = put("extra_instruction", value)

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && apiBaseUrl.isNotBlank()

    private fun put(key: String, value: Any) {
        val obj = json()
        obj.put(key, value)
        store.writeObject(file, obj)
    }

    /** Safe for logs / UI, never includes the key. */
    fun summary(): String =
        "model=$model base=$apiBaseUrl steps=$maxSteps autonomy=${autonomy.name} " +
            "selfImprove=$selfImprove cloud=${if (cloudUrl.isNotBlank()) "on" else "off"} " +
            "selfBuild=${if (githubToken.isNotBlank()) "on" else "off"}"
}
