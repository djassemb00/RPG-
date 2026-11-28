package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.OnFailure
import ai.djb.agent.core.Repeat
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ScheduledTask
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import ai.djb.agent.services.TaskScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the agent schedule its own future work — the most useful form of
 * self-direction it can have without editing its own code.
 */
class ScheduleTools {

    private val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    class ScheduleTask(private val stamp: SimpleDateFormat) : AgentTool {
        override val spec = ToolSpec(
            name = "schedule_task",
            description = """
                جدول مهمة تنفّذ تلقائيًا في الخلفية دون فتح التطبيق.
                ملاحظات مهمة: (١) أفضل ما ينجح في الخلفية هو ما لا يحتاج لمس الشاشة
                (قراءة معلومات، تذكير، إرسال تقرير)، لأن أي إجراء HIGH سيُرفض بلا مستخدم.
                (٢) الحد الأدنى للتكرار 15 دقيقة تفرضه أندرويد.
                الاستخدام: prompt نص المهمة كما لو كتبه المستخدم.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("title", prop("string", "اسم قصير للمهمة"))
                    .put("prompt", prop("string", "نص المهمة الكامل الذي سينفّذه الوكيل"))
                    .put("hour", prop("integer", "الساعة 0-23"))
                    .put("minute", prop("integer", "الدقيقة 0-59"))
                    .put("repeat", prop("string", "التكرار", listOf("once", "hourly", "daily", "weekly")))
                    .put("on_failure", prop("string", "عند الفشل", listOf("skip", "retry_once", "notify"))))
                .put("required", JSONArray().put("title").put("prompt")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val title = args.optString("title").trim()
            val prompt = args.optString("prompt").trim()
            if (title.isBlank() || prompt.isBlank()) return "ERROR: title و prompt مطلوبان."

            val repeat = when (args.optString("repeat", "daily").lowercase()) {
                "once" -> Repeat.ONCE
                "hourly" -> Repeat.HOURLY
                "weekly" -> Repeat.WEEKLY
                else -> Repeat.DAILY
            }
            val onFailure = when (args.optString("on_failure", "notify").lowercase()) {
                "skip" -> OnFailure.SKIP
                "retry_once" -> OnFailure.RETRY_ONCE
                else -> OnFailure.NOTIFY
            }

            val task = ScheduledTask(
                id = UUID.randomUUID().toString().take(8),
                title = title,
                prompt = prompt,
                hour = args.optInt("hour", 9).coerceIn(0, 23),
                minute = args.optInt("minute", 0).coerceIn(0, 59),
                repeat = repeat,
                onFailure = onFailure,
            ).let { it.copy(nextRunAt = it.computeNextRun()) }

            val app = ctx.app as ai.djb.agent.AgentApp
            app.tasks.upsert(task)
            TaskScheduler.scheduleOne(ctx.app, task)

            return "تمت جدولة «$title» (${repeat.label()}) — التشغيل القادم: ${stamp.format(Date(task.nextRunAt))}."
        }
    }

    class ListScheduledTask(override val spec: ToolSpec = ToolSpec(
        name = "list_scheduled_tasks",
        description = "اعرض المهام المجدولة وحالتها وآخر نتيجة.",
        risk = Risk.LOW,
    )) : AgentTool {
        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val app = ctx.app as ai.djb.agent.AgentApp
            val tasks = app.tasks.load()
            if (tasks.isEmpty()) return "لا مهام مجدولة."
            return tasks.joinToString("\n") { t ->
                val next = if (t.nextRunAt > 0) stamp.format(Date(t.nextRunAt)) else "—"
                "- ${t.id} «${t.title}» ${t.repeat.label()} | ${if (t.enabled) "مفعّلة" else "موقوفة"} | " +
                    "القادم=$next | تشغيل=${t.runCount} فشل=${t.failCount} | آخر نتيجة: ${t.lastResult.take(80)}"
            }
        }
    }

    class CancelScheduledTask : AgentTool {
        override val spec = ToolSpec(
            name = "cancel_scheduled_task",
            description = "احذف مهمة مجدولة بالمعرّف أو بالاسم.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("id", prop("string", "معرّف المهمة أو جزء من اسمها")))
                .put("required", JSONArray().put("id")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val key = args.optString("id").trim()
            if (key.isBlank()) return "ERROR: id مطلوب."
            val app = ctx.app as ai.djb.agent.AgentApp
            val task = app.tasks.load().firstOrNull { it.id == key || it.title.contains(key, ignoreCase = true) }
                ?: return "ERROR: لا مهمة تطابق «$key»."
            app.tasks.remove(task.id)
            TaskScheduler.cancel(ctx.app, task.id)
            return "حُذفت المهمة «${task.title}» (${task.id})."
        }
    }
}
