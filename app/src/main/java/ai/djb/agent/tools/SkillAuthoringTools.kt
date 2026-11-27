package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.Skill
import ai.djb.agent.core.SkillStep
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.skills.SkillRuntime
import org.json.JSONArray
import org.json.JSONObject

/**
 * This is where the agent writes its own behaviour.
 *
 * A skill is a declarative plan over existing tools. That keeps self-modification
 * inspectable (plain JSON the user can read), reversible (delete a skill) and
 * incapable of inventing capabilities the app doesn't already have.
 */
class SkillAuthoringTools {

    class CreateSkill : AgentTool {
        override val spec = ToolSpec(
            name = "create_skill",
            description = """
                أنشئ مهارة جديدة (خطة قابلة لإعادة الاستخدام) من خطوات أدوات موجودة.
                steps: مصفوفة من كائنات {"tool": "اسم_الأداة", "args": {...}, "save_as": "اسم_اختياري"}.
                تُتحقق كل خطوة قبل الحفظ؛ إن كانت أداة غير موجودة تُرفض المهارة.
                استبدل القيم المتغيّرة بعلامات مثل {{text}} ثم مرّرها عند التشغيل.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "string").put("description", "معرّف قصير بدون مسافات"))
                    .put("description", JSONObject().put("type", "string"))
                    .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))))
                .put("required", JSONArray().put("name").put("description").put("steps")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val name = args.optString("name").trim().replace(Regex("\\s+"), "_")
            val description = args.optString("description").trim()
            val stepsRaw = args.optJSONArray("steps") ?: return "ERROR: steps مطلوبة."

            if (name.isBlank()) return "ERROR: name مطلوب."
            val registry = ai.djb.agent.core.ToolRegistryHolder.tools()
                ?: return "ERROR: السجل غير جاهز."

            val steps = ArrayList<SkillStep>()
            for (i in 0 until stepsRaw.length()) {
                val obj = stepsRaw.optJSONObject(i) ?: continue
                val step = SkillStep.fromJson(obj)
                if (!registry.has(step.tool)) {
                    return "ERROR: الخطوة ${i + 1} تستخدم أداة غير موجودة '${step.tool}'. " +
                        "الأدوات المتاحة: ${registry.specs().joinToString { it.name }}"
                }
                steps.add(step)
            }
            if (steps.isEmpty()) return "ERROR: المهارة تحتاج خطوة واحدة على الأقل."

            val skills = ctx.store.readSkills().toMutableList()
            val existing = skills.indexOfFirst { it.name == name }
            val skill = Skill(name = name, description = description, steps = steps)
            if (existing >= 0) skills[existing] = skill else skills.add(skill)
            ctx.store.writeSkills(skills)

            return "تم ${if (existing >= 0) "تحديث" else "إنشاء"} المهارة '$name' بخطوات (${steps.size}). " +
                "شغّلها بـ run_skill."
        }
    }

    class SaveSkill : AgentTool {
        override val spec = ToolSpec(
            name = "save_skill",
            description = "احفظ خطة يُعاد استخدامها من خطوات الأدوات. مختصر لـ create_skill.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "string"))
                    .put("description", JSONObject().put("type", "string"))
                    .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))))
                .put("required", JSONArray().put("name").put("description").put("steps")),
            risk = Risk.MEDIUM,
        )

        private val inner = CreateSkill()

        override suspend fun execute(args: JSONObject, ctx: ToolContext) = inner.execute(args, ctx)
    }

    class RunSkill : AgentTool {
        override val spec = ToolSpec(
            name = "run_skill",
            description = "نفّذ مهارة محفوظة. مرّر معاملات لمكان المتغيّرات {{name}}.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "string"))
                    .put("params", JSONObject().put("type", "object")))
                .put("required", JSONArray().put("name")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val name = args.optString("name").trim()
            val registry = ai.djb.agent.core.ToolRegistryHolder.tools()
                ?: return "ERROR: السجل غير جاهز."
            val skill = ctx.store.readSkills().firstOrNull { it.name == name }
                ?: return "ERROR: لا مهارة باسم '$name'. المتاح: ${
                    ctx.store.readSkills().joinToString { it.name }
                }"

            val runtime = SkillRuntime(ctx)
            val params = args.optJSONObject("params") ?: JSONObject()
            val outcome = runtime.run(skill, params) { step ->
                registry.invoke(step.tool, step.args, ai.djb.agent.core.Autonomy.FULL) { _, _ -> true }
            }

            // Update reliability stats — this is the agent learning which plans work.
            val skills = ctx.store.readSkills().toMutableList()
            val idx = skills.indexOfFirst { it.name == name }
            if (idx >= 0) {
                val s = skills[idx]
                skills[idx] = s.copy(
                    runs = s.runs + 1,
                    successes = s.successes + if (outcome.failedAt == null) 1 else 0,
                )
                ctx.store.writeSkills(skills)
            }

            return if (outcome.failedAt == null) {
                "المهارة '$name' نجحت (${skill.steps.size} خطوة).\n" + outcome.transcript
            } else {
                "المهارة '$name' فشلت عند الخطوة ${outcome.failedAt + 1}: ${outcome.error}\n" + outcome.transcript
            }
        }
    }

    class ListSkills : AgentTool {
        override val spec = ToolSpec(
            name = "list_skills",
            description = "اعرض المهارات المحفوظة وموثوقيتها.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val skills = ctx.store.readSkills()
            if (skills.isEmpty()) return "لا مهارات محفوظة بعد. يمكنك إنشاء واحدة بـ create_skill."
            return skills.joinToString("\n") { s ->
                val pct = if (s.runs == 0) "—" else "${(s.reliability * 100).toInt()}%"
                "- ${s.name}: ${s.description} | خطوات=${s.steps.size} | تشغيل=${s.runs} | نجاح=$pct"
            }
        }
    }

    class DeleteSkill : AgentTool {
        override val spec = ToolSpec(
            name = "delete_skill",
            description = "احذف مهارة لم تعد تعمل.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("name")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val name = args.optString("name").trim()
            val skills = ctx.store.readSkills()
            val remaining = skills.filterNot { it.name == name }
            if (remaining.size == skills.size) return "ERROR: لا مهارة باسم '$name'."
            ctx.store.writeSkills(remaining)
            return "تم حذف المهارة '$name'."
        }
    }

    class InspectSkill : AgentTool {
        override val spec = ToolSpec(
            name = "inspect_skill",
            description = "اعرض خطوات مهارة بالتفصيل (JSON) لتشخيص فشلها.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("name")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val name = args.optString("name").trim()
            val skill = ctx.store.readSkills().firstOrNull { it.name == name }
                ?: return "ERROR: لا مهارة باسم '$name'."
            return skill.toJson().toString(2)
        }
    }
}
