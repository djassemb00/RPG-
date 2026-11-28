package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pattern mining: finds work the agent repeats and proposes turning it into a skill.
 *
 * This is mechanical, not magical — no model call. It reads the audit log, finds the
 * same ordered sequence of tools appearing across *different* runs, and reports how
 * much it recurs. A suggestion only becomes a skill when something acts on it, which
 * keeps the "learns by itself" promise honest: it notices, it doesn't silently invent.
 */
class PatternMiner(private val store: JsonStore) {

    data class Sequence(
        val tools: List<String>,
        val occurrences: Int,
        val lastSeen: Long,
        /** Tool arguments from the most recent run — used as the skill's template. */
        val sampleArgs: List<JSONObject>,
    ) {
        fun describe(): String =
            "(${occurrences}×) " + tools.joinToString(" ← ")
    }

    companion object {
        /** Shortest run worth automating; two calls is usually one intention. */
        const val MIN_LENGTH = 3

        /** Repeat count that justifies a suggestion at all. */
        const val MIN_OCCURRENCES = 2

        /** Tools that should never anchor a reusable skill (inspection/state checks). */
        private val NOISE = setOf(
            "list_skills", "inspect_skill", "memory_list", "list_tasks",
            "adb_status", "shell_capability", "inspect_backup", "device_info",
        )
    }

    /**
     * Reads the log and returns recurring tool sequences, longest first.
     *
     * Runs are delimited by `task` lines, so the same three tools twice inside one
     * conversation don't count as a pattern — only repetition across tasks does.
     */
    fun mine(limit: Int = 5): List<Sequence> {
        val lines = store.readLog(2000)
        if (lines.isEmpty()) return emptyList()

        val runs = ArrayList<MutableList<Pair<String, String>>>() // name to args
        var current: MutableList<Pair<String, String>>? = null

        for (line in lines) {
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: continue
            // Log shape (see AgentEngine.logLine): {"at":…, "kind":…, "text":…}
            when (entry.optString("kind")) {
                "task" -> {
                    current?.let { if (it.isNotEmpty()) runs.add(it) }
                    current = ArrayList()
                }
                "tool_start" -> {
                    val text = entry.optString("text")
                    val name = text.substringBefore(' ').trim()
                    if (name.isBlank() || name in NOISE) return@when
                    val args = text.substringAfter(' ', "").trim()
                    val run = current ?: ArrayList<Pair<String, String>>().also { current = it }
                    run.add(name to args)
                }
            }
        }
        current?.let { if (it.isNotEmpty()) runs.add(it) }

        if (runs.isEmpty()) return emptyList()

        // Count every contiguous window of MIN_LENGTH..6 tools across all runs.
        val counts = LinkedHashMap<String, Int>()
        val lastArgs = HashMap<String, Pair<Long, List<String>>>()

        for (run in runs) {
            val names = run.map { it.first }
            if (names.size < MIN_LENGTH) continue
            for (start in 0 until names.size) {
                for (len in MIN_LENGTH..6) {
                    if (start + len > names.size) break
                    val window = names.subList(start, start + len)
                    // Skip windows with a repeat inside: they're usually a retry loop.
                    if (window.toSet().size != window.size) continue
                    val key = window.joinToString(">")
                    counts[key] = (counts[key] ?: 0) + 1
                    lastArgs[key] = System.currentTimeMillis() to
                        run.subList(start, start + len).map { it.second }
                }
            }
        }

        return counts.entries
            .filter { it.value >= MIN_OCCURRENCES }
            .mapNotNull { (key, count) ->
                val meta = lastArgs[key] ?: return@mapNotNull null
                Sequence(
                    tools = key.split(">"),
                    occurrences = count,
                    lastSeen = meta.first,
                    sampleArgs = meta.second.map { raw ->
                        runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                    },
                )
            }
            // Prefer frequent and long: 5 repeats of 5 steps beats 6 repeats of 3 steps.
            .sortedWith(compareByDescending<Sequence> { it.occurrences * it.tools.size }
                .thenByDescending { it.tools.size })
            .take(limit)
    }

    /** Filters out sequences already covered by an existing skill. */
    fun suggestions(limit: Int = 5): List<Sequence> {
        val existing = store.readSkills().map { skill -> skill.steps.map { it.tool } }
        return mine(limit * 3).filter { seq ->
            existing.none { it == seq.tools || (it.containsAll(seq.tools) && it.size <= seq.tools.size + 1) }
        }.take(limit)
    }

    /** Builds a runnable skill from a mined sequence, keeping the last-seen arguments. */
    fun toSkill(sequence: Sequence, name: String, description: String): JSONObject {
        val steps = JSONArray()
        sequence.tools.forEachIndexed { index, tool ->
            val args = sequence.sampleArgs.getOrNull(index) ?: JSONObject()
            steps.put(JSONObject().apply {
                put("tool", tool)
                // Blank out values that look like identity-specific (ids, coordinates,
                // free text) so the skill is reusable rather than a recording.
                put("args", generalize(args))
                if (index == sequence.tools.lastIndex) put("save_as", "result")
            })
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("steps", steps)
        }
    }

    private fun generalize(args: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = args.opt(k)
            when {
                k in setOf("text", "query", "message", "value") -> out.put(k, "{{" + k + "}}")
                v is Number && (k == "x" || k == "y" || k.endsWith("_x") || k.endsWith("_y")) ->
                    out.put(k, "{{" + k + "}}")
                else -> out.put(k, v)
            }
        }
        return out
    }
}
