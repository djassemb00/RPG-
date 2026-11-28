package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Compresses successful runs into short, reusable lessons.
 *
 * This is the closest thing to weight-level learning that a frozen model allows: the
 * agent reads its own successful tool traces and writes a one-line rule about what
 * worked ("for X, read the screen first, then click by text"). Those lessons are injected
 * into every future plan prompt, so the behaviour improves without touching weights —
 * the compression is done by the model, the memory by us.
 *
 * Two guard rails keep it honest:
 *  - a lesson is only built from a run that **succeeded**, never from a plausible story;
 *  - a lesson is capped in number and length, because an ever-growing prompt is how a
 *    context window silently dies.
 */
class LessonLearner(
    private val store: JsonStore,
    private val client: LlmClient,
) {

    companion object {
        private const val FILE = "lessons.json"
        const val MAX_LESSONS = 24
        const val MAX_LESSON_CHARS = 220

        /** Runs shorter than this teach nothing worth keeping. */
        private const val MIN_TOOLS_IN_RUN = 2
    }

    data class Lesson(
        val text: String,
        val sourceTask: String,
        val createdAt: Long,
        val uses: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("text", text)
            put("source_task", sourceTask)
            put("created_at", createdAt)
            put("uses", uses)
        }

        companion object {
            fun fromJson(o: JSONObject) = Lesson(
                text = o.optString("text"),
                sourceTask = o.optString("source_task"),
                createdAt = o.optLong("created_at"),
                uses = o.optInt("uses"),
            )
        }
    }

    fun lessons(): List<Lesson> {
        val arr = store.readArray(FILE)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Lesson::fromJson) }
            .filter { it.text.isNotBlank() }
    }

    fun writeLessons(list: List<Lesson>) {
        store.writeArray(FILE, JSONArray().apply { list.forEach { put(it.toJson()) } })
    }

    fun clear() = writeLessons(emptyList())

    /**
     * Learns a lesson from the most recent successful run in the audit log.
     * Returns null when the run is too thin to teach anything.
     */
    suspend fun learnFromLastRun(): Lesson? {
        val run = lastSuccessfulRun() ?: return null
        if (run.tools.size < MIN_TOOLS_IN_RUN) return null

        val prompt = buildString {
            append("حللت هذه المهمة بنجاح على هاتف أندرويد:\n")
            append("المهمة: ${run.task}\n")
            append("تسلسل الأدوات: ${run.tools.joinToString(" ← ")}\n")
            if (run.result.isNotBlank()) append("النتيجة: ${run.result.take(400)}\n")
            append("\nاكتب **درسًا واحدًا** قصيرًا (سطر واحد، أقل من 200 حرف) يُفيد في مهام مشابهة مستقبلًا.\n")
            append("اجعله إجرائيًا: \"عند X، افعل Y أولًا لأن Z\". لا تكرر المهمة نفسها، ولا تشرح عموميات.\n")
            append("أعد الدرس فقط بلا مقدمات.")
        }

        val response = try {
            client.chat(
                listOf(
                    ChatMessage.system("تستخلص دروسًا عملية موجزة من تجارب ناجحة. أعد سطرًا واحدًا فقط."),
                    ChatMessage.user(prompt),
                ),
                emptyList(),
                AgentPhase.PLAN,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }

        val text = response.content.trim().trim('"', '-', '•').lines().firstOrNull().orEmpty()
        if (text.length < 15) return null

        val lesson = Lesson(
            text = text.take(MAX_LESSON_CHARS),
            sourceTask = run.task.take(160),
            createdAt = System.currentTimeMillis(),
        )

        val existing = lessons().toMutableList()
        // Replace a near-duplicate instead of stacking copies of the same advice.
        val duplicate = existing.indexOfFirst { similar(it.text, lesson.text) }
        if (duplicate >= 0) existing[duplicate] = lesson else existing.add(lesson)

        writeLessons(existing.takeLast(MAX_LESSONS))
        return lesson
    }

    /** Compact block for the planning prompt; empty string when there is nothing to say. */
    fun promptBlock(): String {
        val list = lessons()
        if (list.isEmpty()) return ""
        return "## دروس تعلّمتها من نجاحاتي السابقة\n" +
            list.takeLast(8).joinToString("\n") { "- ${it.text}" }
    }

    private fun similar(a: String, b: String): Boolean {
        val wa = a.lowercase().split(Regex("\\s+")).filter { it.length > 3 }.toSet()
        val wb = b.lowercase().split(Regex("\\s+")).filter { it.length > 3 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        val overlap = wa.intersect(wb).size.toFloat() / minOf(wa.size, wb.size)
        return overlap > 0.6f
    }

    // ---- audit-log reading ----

    private data class Run(val task: String, val tools: List<String>, val result: String, val ok: Boolean)

    /**
     * Reconstructs the last run that ended without an error. Runs are delimited by
     * `task` lines; a tool line starting with `error` or ending in an ERROR result
     * disqualifies the run, because a lesson from a failure is a repair, not a lesson.
     */
    private fun lastSuccessfulRun(): Run? {
        val lines = store.readLog(600)
        val runs = ArrayList<Run>()
        var task = ""
        val tools = ArrayList<String>()
        val results = StringBuilder()
        var failed = false

        fun flush() {
            if (task.isNotBlank() && tools.isNotEmpty()) {
                runs.add(Run(task, tools.toList(), results.toString().trim(), !failed))
            }
            task = ""
            tools.clear()
            results.setLength(0)
            failed = false
        }

        for (line in lines) {
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (entry.optString("kind")) {
                "task" -> {
                    flush()
                    task = entry.optString("text")
                }
                "tool_start" -> {
                    val name = entry.optString("text").substringBefore(' ').trim()
                    if (name.isNotBlank()) tools.add(name)
                }
                "tool_end" -> {
                    val text = entry.optString("text")
                    if (text.contains("-> ERROR") || text.contains("-> DENIED")) failed = true
                    results.append(text.substringAfter("-> ", "").take(200)).append(' ')
                }
                "error", "llm_error" -> failed = true
            }
        }
        flush()

        return runs.lastOrNull { it.ok && it.tools.size >= MIN_TOOLS_IN_RUN }
    }
}
