package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export/import of learned state.
 *
 * These tools deliberately write to a fixed, app-owned folder instead of asking the
 * model for a path: a model-generated path is a place a file can silently go missing,
 * and the user needs to find the backup afterwards. The agent reports the path and the
 * UI offers a share/open action.
 */
class BackupTools {

    class ExportBackup : AgentTool {
        override val spec = ToolSpec(
            name = "export_backup",
            description = """
                صدّر كل ما تعلّمته (المهارات، الذاكرة، القواعد، المهام، المزوّدون، الإعدادات)
                إلى ملف JSON واحد. مفاتيح API تُحذف افتراضيًا.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("include_keys", prop("boolean", "أدرج مفاتيح API؟ افتراضيًا لا")))
                .put("required", JSONArray()),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val includeKeys = args.optBoolean("include_keys", false)
            val file = runCatching { ctx.backup.exportToCache(includeKeys) }.getOrNull()
                ?: return "ERROR: تعذّر إنشاء الملف."
            val summary = ctx.backup.summaryOf(ctx.backup.export(includeKeys))
            return buildString {
                append("صُدّرت نسخة احتياطية: ${file.absolutePath} (${file.length() / 1024} كيلوبايت)\n")
                append("المحتوى: ${summary.skills} مهارة · ${summary.memory} ذكرى · ")
                append("${summary.rules} قاعدة · ${summary.tasks} مهمة · ${summary.providers} مزوّد\n")
                append(
                    if (includeKeys) "تحذير: الملف يحتوي مفاتيح API — احتفظ به في مكان آمن."
                    else "المفاتيح غير مضمنة (آمن للمشاركة)."
                )
            }
        }
    }

    class InspectBackup : AgentTool {
        override val spec = ToolSpec(
            name = "inspect_backup",
            description = "اعرض محتوى آخر نسخة احتياطية أنشأها التطبيق قبل استيرادها.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val candidates = java.io.File(ctx.application.cacheDir, "backups")
                .listFiles()?.toList().orEmpty()
                .filter { it.extension == "json" }
                .sortedByDescending { it.lastModified() }
            if (candidates.isEmpty()) return "لا نسخ احتياطية بعد. أنشئ واحدة بـ export_backup."
            return candidates.take(5).joinToString("\n") { f ->
                val doc = runCatching { JSONObject(f.readText()) }.getOrNull()
                if (doc == null) "- ${f.name}: ملف غير صالح"
                else {
                    val s = ctx.backup.summaryOf(doc)
                    "- ${f.name}: ${s.skills} مهارة · ${s.memory} ذكرى · ${s.rules} قاعدة · ${s.tasks} مهمة"
                }
            }
        }
    }
}
