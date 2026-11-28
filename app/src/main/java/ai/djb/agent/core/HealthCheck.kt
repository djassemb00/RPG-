package ai.djb.agent.core

import org.json.JSONObject

/**
 * Startup self-check. Evaluates the conditions that would make the agent silently
 * useless, and gives the safety valve a real signal to act on.
 *
 * The distinction matters: "no API key yet" is a configuration state, not a fault —
 * rolling back because a user hasn't pasted a key would undo their work for no reason.
 * A fault is something the *app* got wrong, so those are the only triggers.
 */
class HealthCheck(
    private val store: JsonStore,
    private val settings: SettingsStore,
    private val providers: ProviderRegistry,
) {

    data class Report(
        val ok: Boolean,
        val faults: List<String>,
        val notes: List<String>,
    ) {
        fun summary(): String = buildString {
            append(if (ok) "سليم" else "به خلل (${faults.size})")
            if (notes.isNotEmpty()) {
                append(" · ملاحظات: ")
                append(notes.joinToString(" | "))
            }
            if (faults.isNotEmpty()) {
                append(" · الأخطاء: ")
                append(faults.joinToString(" | "))
            }
        }
    }

    /**
     * Runs the checks and records the outcome in the audit log. Called once at startup
     * and whenever the user asks for a check from the UI.
     */
    fun run(): Report {
        val faults = ArrayList<String>()
        val notes = ArrayList<String>()

        // 1. State files must parse. A corrupt skills.json means the agent's learned
        //    behaviour is gone and every skill run will fail — that is a fault.
        for (name in listOf("skills.json", "rules.json", "memory.json", "settings.json")) {
            val raw = runCatching { java.io.File(store.privateDir(), name) }.getOrNull()
            if (raw != null && raw.exists() && raw.length() > 0) {
                val parsed = runCatching { JSONObject(raw.readText()) }.isSuccess ||
                    runCatching {
                        org.json.JSONArray(raw.readText())
                    }.isSuccess
                if (!parsed) faults.add("$name تالف — تعذّر تحليله")
            }
        }

        // 2. Skills must reference tools that still exist. After a patch removes or
        //    renames a tool, every skill using it breaks — better caught at startup.
        val registry = ToolRegistryHolder.tools()
        if (registry != null) {
            val known = registry.specs().map { it.name }.toSet()
            val broken = store.readSkills().filter { skill ->
                skill.steps.any { it.tool !in known }
            }
            if (broken.isNotEmpty()) {
                faults.add("${broken.size} مهارة تشير إلى أدوات غير موجودة: " +
                    broken.take(3).joinToString { it.name })
            }
        } else {
            faults.add("سجل الأدوات لم يُهيّأ")
        }

        // 3. Settings must be coherent. Not a fault if unconfigured, but a model name
        //    with spaces or an empty base URL will 404 on every call.
        if (settings.model.contains(' ')) notes.add("اسم الموديل يحتوي مسافة")
        if (settings.apiBaseUrl.isBlank()) notes.add("عنوان الـ API فارغ")
        if (!settings.isConfigured) notes.add("لم يُضبط مزوّد بعد")

        // 4. Provider chain must not be all-disabled while keys exist.
        val slots = providers.load()
        if (slots.isNotEmpty() && slots.none { it.enabled }) {
            faults.add("كل المزوّدين معطّلون — لن يعمل أي طلب")
        }

        val report = Report(faults.isEmpty(), faults, notes)
        store.appendLog(
            JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("kind", "health")
                put("text", report.summary().take(400))
            }.toString()
        )
        return report
    }
}
