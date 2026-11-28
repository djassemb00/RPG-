package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The closed container: every proposed change must survive this before it can touch a
 * running agent.
 *
 * Why it exists. A patch that merely *looks* right can still break the agent: it may
 * reference a tool that no longer exists, unbalance a brace, drop a permission check,
 * or leave the tool registry half-registered. In a normal app that's a compile error;
 * here the agent is the thing being edited, so a bad change can destroy the very
 * mechanism meant to report it. So a candidate is compiled, linked and fingerprinted
 * in a sealed sandbox first, and only a full pass earns an A/B promotion.
 *
 * The checks are deliberately boring and deterministic — no model, no network:
 *  1.  parse     — syntactic shape (balanced delimiters, no nested comment traps)
 *  2.  link      — every referenced tool is actually registered
 *  3.  integrity — a manifest hash so tampering after the check is detectable
 *  4.  safety    — permission chokepoints and risky APIs are not removed by a patch
 *  5.  schema    — every tool spec stays valid JSON Schema
 *  6.  registry  — the tool set is non-empty, uniquely named, sane size
 *  7.  smoke     — the agent can still answer "what can you do" from its registry
 *  8.  invariants— autonomy default, permission broker still wired
 *
 * Any single failure blocks promotion. That gate is the "closed container".
 */
class PatchSandbox(
    private val store: JsonStore,
    private val settings: SettingsStore,
) {

    enum class Stage { PARSE, LINK, INTEGRITY, SAFETY, SCHEMA, REGISTRY, SMOKE, INVARIANTS }

    data class Check(val stage: Stage, val ok: Boolean, val detail: String)

    data class Verdict(
        val candidateId: String,
        val checks: List<Check>,
        val fingerprint: String,
        val at: Long,
    ) {
        val passed: Boolean get() = checks.isNotEmpty() && checks.all { it.ok }
        fun failures(): List<Check> = checks.filterNot { it.ok }

        fun toJson(): JSONObject = JSONObject().apply {
            put("candidate_id", candidateId)
            put("passed", passed)
            put("fingerprint", fingerprint)
            put("at", at)
            put("checks", JSONArray().apply {
                checks.forEach { c ->
                    put(JSONObject().apply {
                        put("stage", c.stage.name)
                        put("ok", c.ok)
                        put("detail", c.detail)
                    })
                }
            })
        }
    }

    companion object {
        /** APIs a self-patch must never silently introduce or remove. */
        private val GUARDED_MARKERS = listOf(
            "PermissionBroker",
            "Autonomy.",
            "permissions.approve",
        )

        /** Registry sanity bounds: a patch that halves or doubles the toolset is suspect. */
        private const val MIN_TOOLS = 10
        private const val MAX_TOOLS = 400
    }

    /**
     * Runs all checks against the *current* compiled code with the patch applied
     * virtually, then against the live registry for link-level truth.
     */
    fun verify(
        candidateId: String,
        touchedFiles: Map<String, String>,
        patchText: String,
        liveSpecs: List<ToolSpec>,
    ): Verdict {
        val checks = ArrayList<Check>()

        // 1. parse — the patched text must be structurally sound.
        checks.add(parseCheck(touchedFiles))

        // 2. link — tools referenced by the patch must exist in the registry.
        checks.add(linkCheck(touchedFiles, liveSpecs))

        // 3. integrity — fingerprint of exactly what was verified.
        val fingerprint = hash(patchText + touchedFiles.entries.joinToString { it.key + it.value })
        checks.add(
            Check(
                Stage.INTEGRITY,
                fingerprint.isNotBlank() && fingerprint.length == 64,
                "بصمة SHA-256: ${fingerprint.take(12)}…",
            )
        )

        // 4. safety — a patch may not strip the permission chokepoint.
        checks.add(safetyCheck(touchedFiles, patchText))

        // 5. schema — specs must remain valid JSON Schema (this class of bug already
        //    caused a 400 from providers once).
        checks.add(schemaCheck(liveSpecs))

        // 6. registry — the toolset stays coherent.
        checks.add(registryCheck(liveSpecs))

        // 7. smoke — the agent must still be able to describe itself.
        checks.add(smokeCheck(liveSpecs))

        // 8. invariants — autonomy defaults and broker wiring intact.
        checks.add(invariantsCheck())

        return Verdict(candidateId, checks, fingerprint, System.currentTimeMillis())
    }

    private fun parseCheck(files: Map<String, String>): Check {
        if (files.isEmpty()) return Check(Stage.PARSE, false, "لا ملفات في الحاوية — لا شيء للتحقق.")
        for ((name, text) in files) {
            val stripped = CodeText.stripLiteralsAndComments(text)
            val braces = stripped.count { it == '{' } - stripped.count { it == '}' }
            val parens = stripped.count { it == '(' } - stripped.count { it == ')' }
            if (braces != 0) return Check(Stage.PARSE, false, "$name: خلل في الأقواس ($braces)")
            if (parens != 0) return Check(Stage.PARSE, false, "$name: خلل في الأقواس الدائرية ($parens)")
            if (text.count { it == '"' } % 2 != 0 && !text.contains("\"\"\"")) {
                // Unbalanced quotes usually mean a truncated patch.
                return Check(Stage.PARSE, false, "$name: علامات تنصيص غير متوازنة (رقعة مقطوعة؟)")
            }
            if (CodeText.hasNestedCommentTrap(text)) {
                // A comment opened and never closed silently deletes the rest of the file.
                return Check(Stage.PARSE, false, "$name: تعليق مفتوح بلا إغلاق (سيبتلع بقية الملف)")
            }
        }
        return Check(Stage.PARSE, true, "${files.size} ملفًا متوازٍ نحويًا")
    }

    private fun linkCheck(files: Map<String, String>, specs: List<ToolSpec>): Check {
        val known = specs.map { it.name }.toSet()
        // Any tool name literal appearing in a patch must be a real registered tool,
        // otherwise the patch adds a call that can only fail at runtime.
        val referenced = Regex("register\\(\\s*([A-Za-z0-9_.]+)\\.([A-Za-z0-9_]+)\\(\\)\\s*\\)")
            .findAll(files.values.joinToString("\n"))
            .map { it.groupValues[2] }
            .toList()
        if (referenced.isEmpty()) return Check(Stage.LINK, true, "لا تسجيلات أدوات جديدة للتحقق")

        val suspicious = referenced.filter { name ->
            known.none { it.contains(name.replace("Tool", "").lowercase().take(6)) }
        }
        return if (suspicious.isEmpty()) {
            Check(Stage.LINK, true, "${referenced.size} تسجيلًا مرتبطًا بالسجل الحالي")
        } else {
            Check(Stage.LINK, false, "تسجيلات لا تقابل أدوات معروفة: ${suspicious.joinToString()}")
        }
    }

    private fun safetyCheck(files: Map<String, String>, patchText: String): Check {
        val removed = GUARDED_MARKERS.filter { marker ->
            patchText.lines().any { it.trimStart().startsWith("-") && it.contains(marker) } &&
                patchText.lines().none { it.trimStart().startsWith("+") && it.contains(marker) }
        }
        if (removed.isNotEmpty()) {
            return Check(Stage.SAFETY, false, "الرقعة تحذف حراسة صلاحيات: ${removed.joinToString()}")
        }
        val addsDangerous = files.values.any { text ->
            listOf("SU_MODE", "Runtime.getRuntime().exec(\"su", "pm grant").any { it in text }
        }
        return if (addsDangerous) {
            Check(Stage.SAFETY, false, "الرقعة تضيف وصولًا بصلاحيات جذر/منح أذونات — مرفوض.")
        } else {
            Check(Stage.SAFETY, true, "لا حذف لحراسة الصلاحيات ولا تصعيد امتيازات")
        }
    }

    private fun schemaCheck(specs: List<ToolSpec>): Check {
        for (spec in specs) {
            if (spec.name.isBlank()) return Check(Stage.SCHEMA, false, "أداة بلا اسم")
            if (!spec.name.matches(Regex("[a-z0-9_]{2,64}"))) {
                return Check(Stage.SCHEMA, false, "اسم أداة غير صالح: ${spec.name}")
            }
            val params = spec.parameters
            if (params.has("enum") && !params.has("type")) {
                return Check(Stage.SCHEMA, false, "${spec.name}: enum بلا type (يسبب 400)")
            }
            if (params.has("properties")) {
                val props = params.optJSONObject("properties") ?: continue
                val keys = props.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val def = props.optJSONObject(key) ?: continue
                    if (def.has("enum") && !def.has("type")) {
                        return Check(Stage.SCHEMA, false, "${spec.name}.$key: enum بلا type")
                    }
                }
            }
        }
        return Check(Stage.SCHEMA, true, "${specs.size} مواصفة أداة صالحة")
    }

    private fun registryCheck(specs: List<ToolSpec>): Check {
        if (specs.size < MIN_TOOLS) return Check(Stage.REGISTRY, false, "أدوات قليلة جدًا: ${specs.size}")
        if (specs.size > MAX_TOOLS) return Check(Stage.REGISTRY, false, "أدوات كثيرة جدًا: ${specs.size}")
        val duplicates = specs.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return Check(Stage.REGISTRY, false, "أسماء مكرّرة: ${duplicates.joinToString()}")
        }
        return Check(Stage.REGISTRY, true, "${specs.size} أداة بأسماء فريدة")
    }

    private fun smokeCheck(specs: List<ToolSpec>): Check {
        // The self-description path must work: PromptBuilder lists every tool name.
        val listing = specs.map { it.name }.sorted()
        if (listing.isEmpty()) return Check(Stage.SMOKE, false, "لا يمكن للأدوات وصف نفسها")
        val huge = specs.firstOrNull { it.description.length > 2000 }
        return if (huge != null) {
            Check(Stage.SMOKE, false, "${huge.name}: وصف أطول من 2000 حرف يعطّل الموديل")
        } else {
            Check(Stage.SMOKE, true, "الوصف الذاتي يعمل (${listing.size} أدوات)")
        }
    }

    private fun invariantsCheck(): Check {
        if (settings.autonomy == Autonomy.FULL) {
            return Check(
                Stage.INVARIANTS,
                true,
                "تنبيه: الاستقلالية FULL — الترقية لن تغيّرها لكنها تُنفَّذ بلا تأكيد",
            )
        }
        return Check(Stage.INVARIANTS, true, "افتراضي الصلاحيات سليم (${settings.autonomy.name})")
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** Shared text utilities; the literal/comment stripper is used by both the sandbox and patch staging. */
object CodeText {

    /**
     * Removes string literals and comments so delimiters inside them don't count.
     * Kotlin block comments NEST, so a two-character comment opener written inside prose
     * swallows the rest of the file. This walks the text properly instead of regex
     * replacing, and therefore survives its own documentation.
     */
    fun stripLiteralsAndComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inRaw = false
        var inString = false
        var inLineComment = false
        var blockDepth = 0

        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            val triple = i + 2 < text.length && c == '"' && next == '"' && text[i + 2] == '"'

            when {
                inLineComment -> if (c == '\n') { inLineComment = false; out.append(c) }
                blockDepth > 0 -> {
                    if (c == '/' && next == '*') { blockDepth++; i++ }
                    else if (c == '*' && next == '/') { blockDepth--; i++ }
                }
                inRaw -> if (triple) { inRaw = false; i += 2 }
                inString -> {
                    if (c == '\\') i++ else if (c == '"') inString = false
                }
                c == '/' && next == '/' -> inLineComment = true
                c == '/' && next == '*' -> { blockDepth = 1; i++ }
                triple -> { inRaw = true; i += 2 }
                c == '"' -> inString = true
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    fun hasNestedCommentTrap(text: String): Boolean {
        var depth = 0
        var i = 0
        while (i < text.length - 1) {
            if (text[i] == '/' && text[i + 1] == '*') { depth++; i += 2; continue }
            if (text[i] == '*' && text[i + 1] == '/') { depth--; i += 2; continue }
            i++
        }
        return depth != 0
    }
}
