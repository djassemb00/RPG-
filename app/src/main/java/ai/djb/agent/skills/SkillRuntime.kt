package ai.djb.agent.skills

import ai.djb.agent.core.Skill
import ai.djb.agent.core.SkillStep
import ai.djb.agent.core.ToolContext
import org.json.JSONObject

/**
 * Executes a skill's steps, substituting {{placeholders}} and capturing outputs of
 * earlier steps so later steps can reference them as {{step.save_as}}.
 */
class SkillRuntime(private val ctx: ToolContext) {

    data class Outcome(val transcript: String, val failedAt: Int?, val error: String?)

    private val placeholder = Regex("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}")

    fun run(
        skill: Skill,
        params: JSONObject,
        invoke: suspend (SkillStep) -> String,
    ): Outcome {
        val captured = HashMap<String, String>()
        val log = StringBuilder()

        for ((index, rawStep) in skill.steps.withIndex()) {
            val resolvedArgs = resolve(rawStep.args, params, captured)
            val step = rawStep.copy(args = resolvedArgs)

            val result = runCatching { invoke(step) }
                .getOrElse { e -> "ERROR: ${e.message}" }

            log.append("[$index] ${step.tool} ${resolvedArgs.toString().take(200)} → ")
            log.append(result.take(400))
            log.append('\n')

            if (result.startsWith("ERROR") || result.startsWith("DENIED")) {
                return Outcome(log.toString(), index, result)
            }

            rawStep.saveAs?.let { captured[it] = result.take(2000) }
        }

        return Outcome(log.toString(), null, null)
    }

    private fun resolve(source: JSONObject, params: JSONObject, captured: Map<String, String>): JSONObject {
        val text = source.toString()
        val replaced = text.replace(placeholder) { match ->
            val key = match.groupValues[1]
            val value = params.optString(key).ifBlank { captured[key].orEmpty() }
            value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        }
        return runCatching { JSONObject(replaced) }.getOrElse { source }
    }
}
