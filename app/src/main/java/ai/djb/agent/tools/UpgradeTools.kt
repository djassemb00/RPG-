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
 * Self-upgrade tools: verify in the container, promote if it passes, roll back if it
 * doesn't. The three tools exist so the agent can talk about its own upgrade honestly,
 * and so the decision to change itself is always an explicit, reviewable step.
 */
class UpgradeTools {

    class VerifyCandidate : AgentTool {
        override val spec = ToolSpec(
            name = "sandbox_verify",
            description = """
                شغّل الحاوية المغلقة على تغيير مقترح قبل أي ترقية.
                تُنفّذ 8 فحوص (نحو، ربط الأدوات، سلامة الصلاحيات، مخططات، السجل، وصف ذاتي، ثوابت).
                إن فشل فحص واحد تُرفض الترقية. لا ترقية بلا اجتياز.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("patch_id", prop("string", "معرّف رقعة من self_patch"))
                    .put("description", prop("string", "ماذا يفترض أن يغيّر هذا التعديل؟")))
                .put("required", JSONArray().put("description")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val patchId = args.optString("patch_id").trim()
            val description = args.optString("description").trim()
            if (description.isBlank()) return "ERROR: description مطلوب."

            val registry = ToolRegistryHolder.tools() ?: return "ERROR: السجل غير جاهز."
            val specs = registry.specs()

            // Collect exactly what the candidate touches: the patched file plus its diff.
            val files = LinkedHashMap<String, String>()
            var patchText = ""
            if (patchId.isNotBlank()) {
                val patch = ctx.selfCode.listPatches().firstOrNull { it.id == patchId }
                    ?: return "ERROR: لا رقعة بالمعرّف $patchId. استخدم self_patches."
                patchText = ctx.selfCode.diffOf(patchId).orEmpty()
                val content = ctx.selfCode.read(patch.file)
                if (content != null) files[patch.file] = content
            }

            val verdict = ctx.sandbox.verify(patchId.ifBlank { "adhoc" }, files, patchText, specs)
            return buildString {
                append(if (verdict.passed) "✅ اجتازت الحاوية كل الفحوص\n" else "❌ فشلت الحاوية — الترقية ممنوعة\n")
                verdict.checks.forEach { c ->
                    append(if (c.ok) "  ✓ " else "  ✗ ")
                    append(c.stage.name).append(": ").append(c.detail).append('\n')
                }
                append("البصمة: ${verdict.fingerprint.take(16)}…")
            }
        }
    }

    class Promote : AgentTool {
        override val spec = ToolSpec(
            name = "self_promote",
            description = """
                رقِّ نسخة جديدة بعد اجتياز الحاوية. تُحفظ النسخة المستقرة الحالية أولًا
                كصمام أمان. إن لم تجتز الحاوية، يُرفض الطلب ويُذكر السبب.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("label", prop("string", "اسم وصفي للنسخة الجديدة"))
                    .put("patch_id", prop("string", "رقعة اجتازت الحاوية")))
                .put("required", JSONArray().put("label")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val label = args.optString("label").trim().ifBlank { "ترقية" }
            val patchId = args.optString("patch_id").trim()
            val registry = ToolRegistryHolder.tools() ?: return "ERROR: السجل غير جاهز."

            val files = LinkedHashMap<String, String>()
            var patchText = ""
            if (patchId.isNotBlank()) {
                ctx.selfCode.listPatches().firstOrNull { it.id == patchId }?.let { patch ->
                    ctx.selfCode.read(patch.file)?.let { files[patch.file] = it }
                    patchText = ctx.selfCode.diffOf(patchId).orEmpty()
                }
            }

            val verdict = ctx.sandbox.verify(patchId.ifBlank { "adhoc" }, files, patchText, registry.specs())

            return when (val outcome = ctx.upgrade.promote(verdict, label)) {
                is ai.djb.agent.core.SelfUpgrade.PromoteOutcome.Rejected ->
                    "رُفضت الترقية — لم تُلمس النسخة المستقرة:\n" +
                        outcome.reasons.joinToString("\n") { "  ✗ $it" }

                is ai.djb.agent.core.SelfUpgrade.PromoteOutcome.Promoted ->
                    buildString {
                        append("تمت ترقية '${outcome.candidate.label}' (${outcome.candidate.id}).\n")
                        append("النقطة المستقرة المحفوظة: ${outcome.stable?.id ?: "—"}\n")
                        append("إن ظهر خطأ: self_rollback، أو استعادة تلقائية عند فشل السلامة بعد الترقية.")
                    }
            }
        }
    }

    class Rollback : AgentTool {
        override val spec = ToolSpec(
            name = "self_rollback",
            description = "صمّام الأمان: استعد النسخة المستقرة السابقة فورًا وألغِ الترقية الحالية.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("reason", prop("string", "ما الخطأ الذي ظهر؟")))
                .put("required", JSONArray().put("reason")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val reason = args.optString("reason").trim().ifBlank { "طلب المستخدم" }
            val ok = ctx.upgrade.rollback(reason)
            return if (ok) {
                "تمت الاستعادة إلى النسخة المستقرة. السبب: $reason"
            } else {
                "ERROR: لا نسخة مستقرة محفوظة بعد — لم تحدث أي ترقية."
            }
        }
    }

    class Status : AgentTool {
        override val spec = ToolSpec(
            name = "self_status",
            description = "اعرض النسخة المستقرة، المرشّحة، وسجل التراجعات.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val state = ctx.upgrade.state()
            val history = ctx.upgrade.history()
            return buildString {
                append("النسخة المستقرة: ${state.stableId ?: "—"}\n")
                append("المرشّحة النشطة: ${state.candidateId ?: "—"}\n")
                if (state.lastRollbackAt > 0) {
                    append(
                        "آخر استعادة: " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                            .format(java.util.Date(state.lastRollbackAt)) +
                            " — ${state.lastRollbackReason}\n"
                    )
                }
                if (history.isEmpty()) {
                    append("لا نسخ محفوظة بعد.")
                } else {
                    append("السجل:\n")
                    history.take(6).forEach { v ->
                        append("- ${v.id} ${v.label} · ${if (v.sandboxPassed) "اجتازت الحاوية" else "بلا فحص"}")
                        append(if (v.id == state.stableId) " [مستقرة]" else "")
                        append(if (v.id == state.candidateId) " [مرشّحة]" else "")
                        append('\n')
                    }
                }
            }
        }
    }
}
