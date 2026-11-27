package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import org.json.JSONObject

class MemoryTools {

    class Remember : AgentTool {
        override val spec = ToolSpec(
            name = "remember",
            description = "احفظ حقيقة دائمة عن المستخدم أو بيئته (اسمه، تفضيلاته، تطبيقاته المعتادة). مفتاح قصير وقيمة واضحة.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("key", JSONObject().put("type", "string").put("description", "مفتاح قصير بالإنجليزية، مثل user_name"))
                    .put("value", JSONObject().put("type", "string").put("description", "القيمة")))
                .put("required", org.json.JSONArray().put("key").put("value")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val key = args.optString("key").trim()
            val value = args.optString("value").trim()
            if (key.isBlank() || value.isBlank()) return "ERROR: المعاملان key و value مطلوبان."
            val memory = ctx.store.readMemory()
            memory[key] = value
            ctx.store.writeMemory(memory)
            return "تم حفظ '$key'. عدد الحقائق المحفوظة: ${memory.size}."
        }
    }

    class Recall : AgentTool {
        override val spec = ToolSpec(
            name = "recall",
            description = "استرجع حقائق محفوظة. اترك query فارغًا لسرد الكل.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "كلمة بحث اختيارية"))),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val query = args.optString("query").lowercase()
            val memory = ctx.store.readMemory()
            if (memory.isEmpty()) return "الذاكرة فارغة."
            val hits = if (query.isBlank()) memory else memory.filter { (k, v) ->
                k.lowercase().contains(query) || v.lowercase().contains(query)
            }
            if (hits.isEmpty()) return "لا نتائج لـ '$query'."
            return hits.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        }
    }

    class AddRule : AgentTool {
        override val spec = ToolSpec(
            name = "add_rule",
            description = "أضف قاعدة سلوك دائمة يلتزم بها الوكيل دائمًا (مثل: لا تتصل بأحد دون إذني). تُدرج في تعليمات النظام.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("rule", JSONObject().put("type", "string").put("description", "نص القاعدة كما يريدها المستخدم")))
                .put("required", org.json.JSONArray().put("rule")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val rule = args.optString("rule").trim()
            if (rule.isBlank()) return "ERROR: نص القاعدة مطلوب."
            val rules = ctx.store.readRules().toMutableList()
            if (rules.contains(rule)) return "القاعدة موجودة مسبقًا."
            rules += rule
            ctx.store.writeRules(rules)
            return "تمت إضافة القاعدة (${rules.size} قاعدة إجمالًا). ستُطبَّق في المهام القادمة."
        }
    }

    class ListRules : AgentTool {
        override val spec = ToolSpec(
            name = "list_rules",
            description = "اعرض كل قواعد المستخدم الحالية.",
            risk = Risk.LOW,
        )
        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val rules = ctx.store.readRules()
            if (rules.isEmpty()) return "لا قواعد خاصة بعد."
            return rules.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")
        }
    }
}
