package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge tools between "remembers experience" and "learns like a model".
 *
 * `learn_lesson` compresses a successful run into a rule the next plan sees. The two
 * export tools produce fine-tuning data, because experience that only lives on one phone
 * cannot become a better model — and that export is the only honest route to the kind of
 * learning the user compared with LLMs.
 */
class LearningTools {

    class LearnLesson : AgentTool {
        override val spec = ToolSpec(
            name = "learn_lesson",
            description = """
                استخلص درسًا عمليًا واحدًا من آخر مهمة نجحت وضعه في ذاكرتك الدائمة.
                الدروس تُحقن في تخطيطك القادم. لا يُنشأ درس من مهمة فاشلة.
            """.trimIndent(),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val lesson = ctx.lessons.learnFromLastRun()
                ?: return "لم أستخرج درسًا: آخر مهمة ناجحة كانت قصيرة جدًا أو لا يوجد سجل كافٍ."
            return "الدرس المسجّل:\n• ${lesson.text}\n\nمن مهمة: ${lesson.sourceTask}"
        }
    }

    class ListLessons : AgentTool {
        override val spec = ToolSpec(
            name = "list_lessons",
            description = "اعرض الدروس المستخلصة من نجاحاتك السابقة.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val lessons = ctx.lessons.lessons()
            if (lessons.isEmpty()) return "لا دروس بعد. نفّذ مهمة ناجحة ثم learn_lesson."
            return "(${lessons.size} درسًا)\n" + lessons.takeLast(12).joinToString("\n") { "• ${it.text}" }
        }
    }

    class TrainingStats : AgentTool {
        override val spec = ToolSpec(
            name = "training_stats",
            description = "كم مثالًا صالحًا للتدريب جمعت من تشغيلاتك الناجحة، وتوزيع الأدوات.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val stats = ctx.training.stats()
            val examples = stats.optInt("examples")
            if (examples == 0) {
                return "لا أمثلة صالحة بعد. أحتاج مهام نجحت بخطوتين أو أكثر."
            }
            val dist = stats.optJSONObject("tool_distribution")
            val lines = ArrayList<String>()
            if (dist != null) {
                val keys = dist.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    lines.add("  $k: ${dist.optInt(k)}")
                }
            }
            return buildString {
                append("$examples مثالًا صالحًا للتدريب ")
                append("(متوسط ${"%.1f".format(stats.optDouble("avg_steps"))} خطوة).\n")
                append("الأدوات الأكثر حضورًا:\n")
                append(lines.joinToString("\n"))
                append("\n\nيمكن تصديرها كـ JSONL للضبط الدقيق عبر export_training (أو من الإعدادات).")
            }
        }
    }

    class ExportTraining : AgentTool {
        override val spec = ToolSpec(
            name = "export_training",
            description = """
                صدّر تشغيلاتك الناجحة كملف JSONL بصيغة OpenAI (system/user/assistant)
                جاهز للضبط الدقيق على مزوّدك. هذا هو الطريق الحقيقي لتعلّم يشبه LLM:
                تجربتك الموثّقة تتحول إلى أوزان على مزوّدك، لا على الهاتف.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("limit", prop("integer", "أقصى عدد أمثلة (افتراضي 200)")))
                .put("required", JSONArray()),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val limit = args.optInt("limit", 200).coerceIn(1, 500)
            val examples = ctx.training.examples().takeLast(limit)
            if (examples.isEmpty()) {
                return "لا أمثلة صالحة للتصدير. أحتاج مهام نجحت بخطوتين أو أكثر."
            }
            val jsonl = ctx.training.toJsonl(examples, ctx.settings.agentName)

            val dir = java.io.File(ctx.app.cacheDir, "training").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            val file = java.io.File(dir, "djb-training-$stamp.jsonl")
            file.writeText(jsonl)

            return buildString {
                append("صُدّر ${examples.size} مثالًا إلى:\n${file.absolutePath}\n")
                append("الحجم: ${file.length() / 1024} كيلوبايت · صيغة JSONL (سطر = محادثة)\n\n")
                append("للاستخدام: ارفع الملف إلى مزوّد يدعم الضبط الدقيق، ثم استبدل الموديل بالنسخة المدرّبة.")
            }
        }
    }
}
