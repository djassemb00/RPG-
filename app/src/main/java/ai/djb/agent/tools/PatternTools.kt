package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.PatternMiner
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns repetition into automation.
 *
 * The agent can look at its own work history and say "I did these five things three
 * times now" — then, with the user's approval, save that as a skill. Nothing is
 * created automatically: noticing is free, committing is a decision.
 */
class PatternTools {

    class SuggestPatterns : AgentTool {
        override val spec = ToolSpec(
            name = "suggest_skills",
            description = """
                حلّل سجل عملك واقترح ما تكرّر: تسلسلات أدوات تكررت في مهام مختلفة
                وتستحق أن تصبح مهارة. لا ينشئ شيئًا، بل يعرض الاقتراحات فقط.
            """.trimIndent(),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val miner = PatternMiner(ctx.store)
            val found = miner.suggestions()
            if (found.isEmpty()) {
                return "لا نمط متكرر بعد. أحتاج نفس التسلسل (3 خطوات أو أكثر) في مهمتين مختلفتين على الأقل."
            }
            return buildString {
                append("تورطت في هذه الأنماط:\n")
                found.forEachIndexed { i, seq ->
                    append("${i + 1}. ${seq.describe()}\n")
                }
                append("\nاستخدم mine_skill لتحويل أي منها إلى مهارة (سأطلب موافقتك).")
            }
        }
    }

    class MineSkill : AgentTool {
        override val spec = ToolSpec(
            name = "mine_skill",
            description = """
                حوّل نمطًا متكررًا إلى مهارة محفوظة. مرّر name (المعرّف) وapproach
                (ترتيب الأدوات كما ظهر في suggest_skills)، واختياريًا description.
                القيم المتغيّرة تُستبدل بعلامات {{…}} لتصبح المهارة قابلة لإعادة الاستخدام.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", prop("string", "معرّف قصير بلا مسافات"))
                    .put("approach", prop("string", "تسلسل الأدوات مفصولًا بـ <- أو > أو أسطر"))
                    .put("description", prop("string", "ماذا تفعل المهارة؟")))
                .put("required", JSONArray().put("name").put("approach")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val name = args.optString("name").trim().replace(Regex("\\s+"), "_")
            if (name.isBlank()) return "ERROR: name مطلوب."

            val wanted = args.optString("approach")
                .split(Regex("<->|<-|->|>|,|\\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (wanted.isEmpty()) return "ERROR: لم أستخرج أسماء أدوات من approach."

            val miner = PatternMiner(ctx.store)
            val all = miner.mine(20)
            // Match on tool names, ignoring order of the offered alternatives.
            val match = all.firstOrNull { it.tools == wanted }
                ?: all.firstOrNull { it.tools.size == wanted.size && it.tools.toSet() == wanted.toSet() }
                ?: return buildString {
                    append("لم أجد نمطًا مطابقًا. الأنماط المتاحة:\n")
                    all.forEach { append("- ${it.describe()}\n") }
                }

            val registry = ai.djb.agent.core.ToolRegistryHolder.tools()
                ?: return "ERROR: السجل غير جاهز."
            val bad = match.tools.firstOrNull { !registry.has(it) }
            if (bad != null) return "ERROR: النمط يشير إلى أداة غير موجودة '$bad'."

            val payload = miner.toSkill(
                match,
                name,
                args.optString("description").ifBlank { "مهارة مستخرجة من ${match.occurrences} تشغيلات سابقة." },
            )

            // Reuse create_skill so validation and storage stay in one place.
            return SkillAuthoringTools.CreateSkill().execute(payload, ctx)
        }
    }
}
