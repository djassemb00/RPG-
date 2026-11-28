package ai.djb.agent.skills

import ai.djb.agent.core.Skill
import ai.djb.agent.core.SkillBranch
import ai.djb.agent.core.SkillStep
import ai.djb.agent.core.ToolContext
import org.json.JSONObject

/**
 * Executes a skill's steps, substituting {{placeholders}} and capturing outputs of
 * earlier steps so later steps can reference them as {{step.save_as}}.
 *
 * Three things make a plan survive contact with a real phone:
 *  - `retry`: a flaky step (network, animation, a screen still loading) gets another go
 *  - `stop_on_error=false`: a step is allowed to fail without killing the plan
 *  - `branches`: the result of a step decides which follow-up steps run, which is how
 *    a plan recovers instead of dying ("didn't find the button → go through Settings")
 */
class SkillRuntime(private val ctx: ToolContext) {

    data class Outcome(
        val transcript: String,
        val failedAt: Int?,
        val error: String?,
        /** Steps executed, including branch steps — useful for honest reporting. */
        val executed: Int = 0,
    )

    private val placeholder = Regex("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}")

    suspend fun run(
        skill: Skill,
        params: JSONObject,
        invoke: suspend (SkillStep) -> String,
    ): Outcome {
        val captured = HashMap<String, String>()
        val log = StringBuilder()
        var executed = 0

        for ((index, rawStep) in skill.steps.withIndex()) {
            val resolvedArgs = resolve(rawStep.args, params, captured)
            val step = rawStep.copy(args = resolvedArgs)

            // Run with retries. Only the final attempt decides the step's fate.
            var result = ""
            var attempts = 0
            while (true) {
                attempts++
                result = try {
                    invoke(step)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }
                executed++
                if (!isFailure(result) || attempts > step.retry) break
                log.append("[$index] محاولة $attempts فشلت، إعادة المحاولة…\n")
            }

            log.append("[$index] ${step.tool} ${resolvedArgs.toString().take(200)} → ")
            log.append(result.take(400))
            log.append('\n')

            val failed = isFailure(result)
            if (failed && step.stopOnError) {
                return Outcome(log.toString(), index, result, executed)
            }
            if (!failed) rawStep.saveAs?.let { captured[it] = result.take(2000) }

            // Conditional follow-ups, evaluated against the step's own result.
            for (branch in step.branches) {
                if (!matches(branch, result)) continue
                log.append("[$index] شرط «${branch.when_}» تحقّق → ${branch.then.size} خطوة\n")
                val nested = runSteps(branch.then, params, captured, invoke, "$index.y")
                log.append(nested.transcript)
                executed += nested.executed
                if (nested.failedAt != null) {
                    return Outcome(log.toString(), index, nested.error, executed)
                }
            }
        }

        return Outcome(log.toString(), null, null, executed)
    }

    /** Runs a nested list (branch body) with the same substitution and capture rules. */
    private suspend fun runSteps(
        steps: List<SkillStep>,
        params: JSONObject,
        captured: MutableMap<String, String>,
        invoke: suspend (SkillStep) -> String,
        prefix: String,
    ): Outcome {
        val log = StringBuilder()
        var executed = 0
        for ((i, rawStep) in steps.withIndex()) {
            val step = rawStep.copy(args = resolve(rawStep.args, params, captured))
            val result = try {
                invoke(step)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            executed++
            log.append("[$prefix$i] ${step.tool} → ${result.take(300)}\n")
            if (isFailure(result) && step.stopOnError) {
                return Outcome(log.toString(), i, result, executed)
            }
            if (!isFailure(result)) rawStep.saveAs?.let { captured[it] = result.take(2000) }
        }
        return Outcome(log.toString(), null, null, executed)
    }

    private fun isFailure(result: String) =
        result.startsWith("ERROR") || result.startsWith("DENIED")

    private fun matches(branch: SkillBranch, result: String): Boolean {
        val hit = result.contains(branch.when_, ignoreCase = true)
        return if (branch.contains) hit else !hit
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
