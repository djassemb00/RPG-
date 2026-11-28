package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.ToolRegistryHolder
import ai.djb.agent.core.prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adding a capability it does not have.
 *
 * Read the tools below in order and the workflow is visible:
 *   read existing source → write a new file → verify → build → read real compiler
 *   errors → fix → build again → publish.
 *
 * The gate is honest: a build that fails publishes nothing, so the running app can never
 * be replaced by a broken one. This is what makes "add voice chat to yourself" a task the
 * agent can actually attempt instead of a promise it can only describe.
 */
class SelfBuildTools {

    class WriteFile : AgentTool {
        override val spec = ToolSpec(
            name = "source_write",
            description = """
                اكتب ملف كود جديد كاملًا (أو استبدل ملفًا موجودًا) داخل مصدرك.
                يُرفض الكتابة إن كان الكود غير متوازن الأقواس أو النص قاصرًا، وممنوع تعديل
                الملفات التي تحمي الوكيل. بعد الكتابة استخدم self_build.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", prop("string", "مثل app/src/main/java/ai/djb/agent/ui/VoiceScreen.kt"))
                    .put("content", prop("string", "الكود الكامل للملف")))
                .put("required", JSONArray().put("path").put("content")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val path = args.optString("path").trim()
            val content = args.optString("content")

            if (path.isBlank()) return "ERROR: path مطلوب."
            if (content.length < 80) return "ERROR: الكود قصير جدًا (${content.length} حرفًا)."

            // A brand-new file may reference a tool that doesn't exist yet; catch the
            // obvious case before spending a build cycle on it.
            val registry = ToolRegistryHolder.tools()
            if (registry != null) {
                val suspicious = Regex("register\\(\\s*[A-Za-z0-9_.]+\\.([A-Za-z0-9_]+)\\(\\)\\s*\\)")
                    .findAll(content).map { it.groupValues[1] }.toList()
                if (suspicious.isNotEmpty()) {
                    return "ERROR: الملف يجرّب تسجيل أدوات (${suspicious.joinToString()}). " +
                        "أضف الأدوات إلى ToolRegistry.kt عبر رقعة بدل وقت البناء."
                }
            }

            val result = ctx.selfCode.stageFullWrite(path, content)
            return result.fold(
                onSuccess = {
                    "كُتب الملف في منطقة التجهيز: $path (${content.length / 1024} كيلوبايت).\n" +
                        "الخطوة التالية: self_build لبناء نسخة جديدة."
                },
                onFailure = { "ERROR: ${it.message}" },
            )
        }
    }

    class Staged : AgentTool {
        override val spec = ToolSpec(
            name = "source_staged",
            description = "اعرض الملفات المكتوبة والمنتظرة للبناء.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val files = ctx.selfCode.stagedWrites()
            if (files.isEmpty()) return "لا ملفات مجهّزة. استخدم source_write."
            return files.joinToString("\n") { (path, file) ->
                "- $path (${file.length() / 1024} كيلوبايت)"
            }
        }
    }

    class Build : AgentTool {
        override val spec = ToolSpec(
            name = "self_build",
            description = """
                ابنِ نسخة جديدة تحتوي ملفاتك المجهّزة عبر GitHub Actions.
                لا يُنشر شيء إن فشل البناء — التطبيق الحالي يبقى سليمًا.
                بعد البدء استخدم self_build_status لقراءة أخطاء المُصرِّف الحقيقية.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("reason", prop("string", "لماذا تبني هذه النسخة؟")))
                .put("required", JSONArray().put("reason")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val reason = args.optString("reason").trim()
            if (reason.isBlank()) return "ERROR: reason مطلوب."

            if (!ctx.github.isConfigured()) {
                return "ERROR: البناء الذاتي غير مضبوط. الإعدادات ← المتصفح: " +
                    "توكن GitHub (صلاحية Actions + Contents) واسم المستودع."
            }

            val staged = ctx.selfCode.stagedWrites()
            if (staged.isEmpty()) return "ERROR: لا ملفات مجهّزة. استخدم source_write أولًا."

            val files = staged.associate { (path, file) -> path to file.readText() }
            val dispatch = ctx.github.dispatchSelfBuild(files, reason)
            return dispatch.fold(
                onSuccess = {
                    "بدأ البناء (${files.size} ملفًا).\n" +
                        "الأمر: ${ctx.settings.githubWorkflow} على ${ctx.settings.githubBranch}\n" +
                        "انتظر ~3 دقائق ثم استخدم self_build_status."
                },
                onFailure = { "ERROR: ${it.message}" },
            )
        }
    }

    class BuildStatus : AgentTool {
        override val spec = ToolSpec(
            name = "self_build_status",
            description = """
                اقرأ حالة البناء الذاتي وأخطاء المُصرِّف الحقيقية إن فشل.
                استخدم الأخطاء لتصحيح الملف ثم self_build مرة أخرى.
            """.trimIndent(),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.github.isConfigured()) return "ERROR: البناء الذاتي غير مضبوط."

            val run = ctx.github.latestRun()
                ?: return "ERROR: تعذّرت قراءة حالة الـ workflow (تحقّق من المستودع والتوكن)."

            if (!run.finished) {
                return "البناء قيد التنفيذ (${run.status}).\n${run.url}\n" +
                    "انتظر قليلًا ثم اسأل مرة أخرى."
            }

            if (run.ok) {
                return "✅ نجح البناء ونُشر APK جديد.\n${run.url}\n" +
                    "ثبّت النسخة من الإصدار djb-ai-debug لتظهر القدرة الجديدة."
            }

            val report = ctx.github.readReport()
            val errors = report?.errors.orEmpty().take(15)
            return buildString {
                append("❌ فشل البناء (${run.conclusion}).\n")
                append(run.url).append("\n\n")
                if (errors.isEmpty()) {
                    append("لم أقرأ أخطاء المُصرِّف — افتح رابط التشغيل.")
                } else {
                    append("أخطاء المُصرِّف الحقيقية:\n")
                    errors.forEach { append("• ").append(it.take(300)).append('\n') }
                    append("\nصحّح الملف بـ source_write ثم أعد self_build.")
                }
            }
        }
    }
}
