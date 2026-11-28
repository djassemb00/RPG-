package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading and patching its own source.
 *
 * The honest boundary: an installed APK cannot rewrite its own compiled code. What it
 * *can* do — and what these tools do — is read the exact source it was built from, find
 * a defect, and stage an applyable unified diff. The user reviews, exports, and applies
 * it. That is real self-development with a human in the loop, not a simulation of it.
 */
class SelfCodeTools {

    class ListSource : AgentTool {
        override val spec = ToolSpec(
            name = "source_list",
            description = "اعرض ملفات الكود المصدري المتاحة للقراءة (لقطة من بناء التطبيق هذا).",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val files = ctx.selfCode.list()
            if (files.isEmpty()) return "لقطة المصدر غير مضمّنة في هذا البناء."
            return "${files.size} ملفًا:\n" + files.joinToString("\n") {
                "- ${it.relativePath} (${it.bytes / 1024} كيلوبايت)"
            }
        }
    }

    class ReadSource : AgentTool {
        override val spec = ToolSpec(
            name = "source_read",
            description = """
                اقرأ كودك المصدري. مرّر file (اسم أو مسار) واختياريًا from/to لقراءة نطاق أسطر.
                استخدمه قبل أي تعديل: لا تخمّن محتوى الكود.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("file", prop("string", "مثل core/AdbBridge.kt أو core__AdbBridge.kt"))
                    .put("from", prop("integer", "أول سطر"))
                    .put("to", prop("integer", "آخر سطر")))
                .put("required", JSONArray().put("file")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val file = args.optString("file")
            if (file.isBlank()) return "ERROR: file مطلوب."
            val from = args.optInt("from", 0)
            val to = args.optInt("to", 0)

            if (from > 0 && to >= from) {
                return ctx.selfCode.lines(file, from, to)
                    ?: "ERROR: الملف غير موجود في اللقطة: $file"
            }
            return ctx.selfCode.read(file)
                ?: "ERROR: الملف غير موجود في اللقطة: $file. استخدم source_list."
        }
    }

    class SearchSource : AgentTool {
        override val spec = ToolSpec(
            name = "source_search",
            description = "ابحث في كل كودك المصدري عن نص أو اسم دالة.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("query", prop("string", "النص المطلوب البحث عنه")))
                .put("required", JSONArray().put("query")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val query = args.optString("query").trim()
            if (query.isBlank()) return "ERROR: query مطلوب."
            val hits = ctx.selfCode.search(query)
            if (hits.isEmpty()) return "لا نتائج لـ «$query»."
            return hits.joinToString("\n") { (file, line) -> "$file:$line" }
        }
    }

    class ProposePatch : AgentTool {
        override val spec = ToolSpec(
            name = "self_patch",
            description = """
                اقترح تعديلًا على كودك: مرّر file، وfind (نص موجود مرة واحدة فقط)،
                وreplace (البديل)، وreason (لماذا). يُنشئ diff موحدًا ويُخزّنه للمراجعة.
                لا يُطبَّق داخل التطبيق — الملف الناتج يُطبَّق على المستودع ويُبنى من جديد.
                يُرفض التعديل إن كان find غامضًا أو إن كسر توازن الأقواس.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("file", prop("string", "مسار الملف"))
                    .put("find", prop("string", "النص الحالي (فريد)"))
                    .put("replace", prop("string", "النص الجديد"))
                    .put("reason", prop("string", "سبب التعديل")))
                .put("required", JSONArray().put("file").put("find").put("replace").put("reason")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val file = args.optString("file")
            val find = args.optString("find")
            val replace = args.optString("replace")
            val reason = args.optString("reason").trim()

            if (reason.isBlank()) return "ERROR: reason مطلوب — التعديل بلا سبب مرفوض."
            if (find.length > 4000) return "ERROR: find طويل جدًا؛ استخدم نطاقًا أصغر."
            if (replace.length > 8000) return "ERROR: replace طويل جدًا."

            val result = ctx.selfCode.stagePatch(file, find, replace, reason)
            return result.fold(
                onSuccess = { (patch, diff) ->
                    buildString {
                        append("أُنشئت رقعة ${patch.id} لـ ${patch.file}.\n")
                        append("السبب: ${patch.reason}\n\n")
                        append(diff.take(2500))
                        append("\n\nلم تُطبَّق بعد. صدّرها من الإعدادات ← كودي، أو طبّقها على المستودع.")
                    }
                },
                onFailure = { "ERROR: ${it.message}" },
            )
        }
    }

    class ListPatches : AgentTool {
        override val spec = ToolSpec(
            name = "self_patches",
            description = "اعرض الرقع التي اقترحتها على كودك وسبب كل واحدة.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val patches = ctx.selfCode.listPatches()
            if (patches.isEmpty()) return "لا رقع بعد. اقرأ الكود بـ source_read ثم اقترح بـ self_patch."
            return patches.take(10).joinToString("\n") { p ->
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(p.createdAt))
                "- ${p.id} · ${p.file} · $stamp · ${if (p.applied) "مطبّقة" else "بانتظار المراجعة"}\n  ${p.reason}"
            }
        }
    }
}
