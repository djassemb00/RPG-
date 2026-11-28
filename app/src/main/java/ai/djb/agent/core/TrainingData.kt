package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the agent's own history into fine-tuning data.
 *
 * This is the bridge the user asked for: the frozen model cannot learn on-device, but the
 * *experience* of this installation can be exported as JSONL in the OpenAI chat format and
 * used to fine-tune a model on any provider that supports it. What comes back is a model
 * that has absorbed this phone, these apps, these habits — the qualitative gain of LLM
 * learning, sourced from real verified work rather than scraped text.
 *
 * Only successful runs are exported. Training on failures teaches the wrong thing, and a
 * dataset is only as good as its labels — here the label *is* the observed outcome.
 */
class TrainingData(private val store: JsonStore) {

    companion object {
        /** Minimum steps for a run to be worth learning from. */
        private const val MIN_TOOLS = 2
        const val MAX_EXAMPLES = 500
    }

    data class Example(
        val task: String,
        val tools: List<String>,
        val result: String,
    )

    /** Reconstructs successful runs from the audit log (same delimiting as LessonLearner). */
    fun examples(): List<Example> {
        val lines = store.readLog(4000)
        val out = ArrayList<Example>()
        var task = ""
        val tools = ArrayList<String>()
        val result = StringBuilder()
        var failed = false

        fun flush() {
            if (task.isNotBlank() && tools.size >= MIN_TOOLS && !failed) {
                out.add(Example(task, tools.toList(), result.toString().trim()))
            }
            task = ""
            tools.clear()
            result.setLength(0)
            failed = false
        }

        for (line in lines) {
            val e = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (e.optString("kind")) {
                "task" -> { flush(); task = e.optString("text") }
                "tool_start" -> e.optString("text").substringBefore(' ').trim()
                    .takeIf { it.isNotBlank() }?.let { tools.add(it) }
                "tool_end" -> {
                    val text = e.optString("text")
                    if (text.contains("-> ERROR") || text.contains("-> DENIED")) failed = true
                    result.append(text.substringAfter("-> ", "").take(300)).append(' ')
                }
                "error", "llm_error" -> failed = true
            }
        }
        flush()
        return out.takeLast(MAX_EXAMPLES)
    }

    /**
     * OpenAI-compatible JSONL. Each line is one training conversation:
     * system (who the agent is) → user (the task) → assistant (the plan that worked).
     *
     * Deliberately *not* including tool schemas: fine-tuning a model to emit tool calls
     * requires the provider's exact format per model, and guessing it produces a dataset
     * that trains the wrong thing. The plan-as-text format is stable across providers.
     */
    fun toJsonl(examples: List<Example> = examples(), agentName: String = "djb"): String =
        examples.joinToString("\n") { ex ->
            JSONObject().apply {
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "أنت $agentName، وكيل أندرويد عملي. تخطّط بمهارات وأدوات متاحة، " +
                                "وتذكر النتيجة الحقيقية فقط."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", ex.task)
                    })
                    put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", renderPlan(ex))
                    })
                })
            }.toString()
        }

    /** The assistant target: the exact successful sequence plus its observed outcome. */
    private fun renderPlan(ex: Example): String = buildString {
        append("الخطة التي نجحت:\n")
        ex.tools.forEachIndexed { i, tool -> append("${i + 1}. $tool\n") }
        if (ex.result.isNotBlank()) {
            append("\nالنتيجة الفعلية: ")
            append(ex.result.take(200))
        }
    }

    /** Human-readable stats so the user knows what they are about to train on. */
    fun stats(): JSONObject {
        val ex = examples()
        val distribution = JSONObject()
        ex.flatMap { it.tools }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(15)
            .forEach { (tool, count) -> distribution.put(tool, count) }

        return JSONObject().apply {
            put("examples", ex.size)
            put("avg_steps", if (ex.isEmpty()) 0.0 else ex.sumOf { it.tools.size }.toDouble() / ex.size)
            put("tool_distribution", distribution)
        }
    }
}
