package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Growth tools. Split deliberately into "look" (free, safe) and "act" (changes what the
 * agent is allowed to do), so the agent's ability to change itself is always visible as
 * a distinct, high-risk step rather than a side effect of a background loop.
 */
class GrowthTools {

    class Assess : AgentTool {
        override val spec = ToolSpec(
            name = "growth_report",
            description = """
                قيّم نموّك من سجلّك الحقيقي: موثوقية كل مهارة، ما يفشل، ما تقاعد،
                وهل تستحق خطوة استقلالية. للقراءة فقط.
            """.trimIndent(),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String =
            ctx.growth.assess().summary()
    }

    class Improve : AgentTool {
        override val spec = ToolSpec(
            name = "growth_improve",
            description = """
                شغّل حلقة التعلّم: أصلح المهارات الفاشلة (بإعادة كتابتها والتحقق منها في الحاوية)
                وقاعد المهارات التي تجاوزت حد المحاولات. مرّر apply=false للتجربة بلا تطبيق.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("apply", prop("boolean", "طبّق التغييرات فعلًا؟ افتراضيًا نعم")))
                .put("required", JSONArray()),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val apply = args.optBoolean("apply", true)
            val result = ctx.growth.grow(apply)
            return buildString {
                append(if (apply) "نتيجة حلقة التعلّم:\n" else "تجربة بلا تطبيق:\n")
                append(result.note)
                if (result.repaired.isNotEmpty()) {
                    append("\nأُصلحت: ${result.repaired.joinToString()}")
                }
                if (result.retired.isNotEmpty()) {
                    append("\nتقاعدت: ${result.retired.joinToString()}")
                }
                if (result.failed.isNotEmpty()) {
                    append("\nتعذّر: ${result.failed.joinToString()}")
                }
            }
        }
    }

    class Graduate : AgentTool {
        override val spec = ToolSpec(
            name = "growth_graduate",
            description = """
                ارفع استقلاليتك درجة واحدة إذنًا بالأدلة (20 تشغيلًا بنجاح 80%+).
                لا قفز بين المستويات: ASK ← BALANCED ← FULL. السجل الضعيف يخفضها فورًا.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("apply", prop("boolean", "طبّق الترقية؟ افتراضيًا لا — اعرض أولًا")))
                .put("required", JSONArray()),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val apply = args.optBoolean("apply", false)
            val outcome = ctx.growth.graduate(apply)
            return when {
                outcome.from == outcome.to && !outcome.applied ->
                    "لا تغيير: الاستقلالية ${outcome.from.name} — ${outcome.why}"
                outcome.applied ->
                    "تمت الترقية: ${outcome.from.name} ← ${outcome.to.name}. السبب: ${outcome.why}"
                else ->
                    "مؤهّل للترقية: ${outcome.from.name} ← ${outcome.to.name} (${outcome.why}). " +
                        "لم تُطبَّق بعد — استخدم apply=true."
            }
        }
    }
}
