package ai.djb.agent.services

import ai.djb.agent.AgentApp
import ai.djb.agent.core.AgentNotifications
import ai.djb.agent.core.LogLevel
import ai.djb.agent.core.Repeat
import ai.djb.agent.core.ScheduledTask
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Schedules agent tasks so they run without the app being open.
 *
 * Android constraints shape the design honestly:
 *  - WorkManager is the only reliable mechanism without a foreground service.
 *  - Periodic work has a 15-minute floor, so sub-15-minute cadences are impossible.
 *  - A worker runs with no Activity, so any HIGH-risk tool that needs a tap will be
 *    denied by the permission gate; background tasks must be written to avoid them
 *    (or run in a mode the user explicitly allows). We report that, not hide it.
 */
object TaskScheduler {

    private const val WORK_PREFIX = "agent_task_"
    private const val TICK_WORK = "agent_schedule_tick"

    fun scheduleAll(context: Context) {
        val app = context.applicationContext as AgentApp
        val tasks = app.tasks.load()
        tasks.forEach { scheduleOne(context, it) }

        // A single periodic worker that catches tasks whose OneTime work was lost
        // (device rebooted, app force-stopped, etc.).
        val tick = PeriodicWorkRequestBuilder<TaskTickWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(TICK_WORK, ExistingPeriodicWorkPolicy.KEEP, tick)
    }

    fun scheduleOne(context: Context, task: ScheduledTask) {
        val wm = WorkManager.getInstance(context)

        if (!task.enabled) {
            wm.cancelUniqueWork(WORK_PREFIX + task.id)
            return
        }
        when (task.repeat) {
            Repeat.HOURLY, Repeat.DAILY, Repeat.WEEKLY -> {
                val intervalMinutes = when (task.repeat) {
                    Repeat.HOURLY -> 60L
                    Repeat.DAILY -> 24L * 60L
                    Repeat.WEEKLY -> 7L * 24L * 60L
                    else -> 60L
                }.coerceAtLeast(15L)
                val request = PeriodicWorkRequestBuilder<TaskWorker>(intervalMinutes, TimeUnit.MINUTES)
                    .setInitialDelay(initialDelayMinutes(task), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(androidx.work.workDataOf(TaskWorker.KEY_TASK_ID to task.id))
                    .build()
                wm.enqueueUniquePeriodicWork(
                    WORK_PREFIX + task.id,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
            Repeat.ONCE -> {
                val request = OneTimeWorkRequestBuilder<TaskWorker>()
                    .setInitialDelay(initialDelayMinutes(task), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(androidx.work.workDataOf(TaskWorker.KEY_TASK_ID to task.id))
                    .build()
                wm.enqueueUniqueWork(WORK_PREFIX + task.id, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    fun cancel(context: Context, taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + taskId)
    }

    private fun initialDelayMinutes(task: ScheduledTask): Long {
        val next = task.computeNextRun()
        val deltaMs = next - System.currentTimeMillis()
        return (deltaMs / 60_000L).coerceAtLeast(0L)
    }
}

/** Executes one scheduled task in the background. */
class TaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val id = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val app = applicationContext as AgentApp
        val task = app.tasks.load().firstOrNull { it.id == id } ?: return@withContext Result.success()

        if (!task.enabled) return@withContext Result.success()

        // A background run has no foreground UI, so make that explicit in the log
        // instead of silently failing every screen-touching tool.
        app.bus.emit(
            ai.djb.agent.core.AgentEvent.Log(
                "تنفيذ مجدول: ${task.title} (بدون واجهة — أدوات الشاشة سترفض)",
                LogLevel.INFO,
            )
        )
        AgentNotifications.show(applicationContext, "مهمة مجدولة: ${task.title}")

        val answer = try {
            app.engine.runBlockingTask(task.prompt)
        } catch (e: Exception) {
            val msg = e.message ?: "فشل غير معروف"
            app.store.appendLog("""{"at":${System.currentTimeMillis()},"kind":"scheduled_error","text":"$id: $msg"}""")
            saveOutcome(app, task, ok = false, result = msg)
            AgentNotifications.clear(applicationContext)
            return@withContext if (task.onFailure == ai.djb.agent.core.OnFailure.RETRY_ONCE) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        val ok = answer.isNotBlank()
        saveOutcome(app, task, ok = ok, result = answer.take(500))
        AgentNotifications.show(
            applicationContext,
            "${task.title}: ${if (ok) "تم" else "انتهى بلا نتيجة"}",
        )
        Result.success()
    }

    private fun saveOutcome(app: AgentApp, task: ScheduledTask, ok: Boolean, result: String) {
        val now = System.currentTimeMillis()
        val finished = task.copy(
            lastRunAt = now,
            lastResult = result,
            lastOk = ok,
            runCount = task.runCount + 1,
            failCount = task.failCount + if (ok) 0 else 1,
            // ONCE tasks disable themselves after a single run.
            enabled = if (task.repeat == Repeat.ONCE) false else task.enabled,
            nextRunAt = if (task.repeat == Repeat.ONCE) 0 else task.computeNextRun(now),
        )
        app.tasks.upsert(finished)
        app.store.appendLog(
            """{"at":$now,"kind":"scheduled_run","text":"${task.id}: ${if (ok) "ok" else "fail"} ${result.take(120)}"}"""
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}

/**
 * Safety net: if a device reboot or force-stop lost the OneTime work, this picks up
 * anything whose nextRunAt has already passed.
 */
class TaskTickWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val app = applicationContext as AgentApp
        app.tasks.rearmAll()
        val due = app.tasks.due()
        due.forEach { TaskScheduler.scheduleOne(applicationContext, it) }
        app.bus.emit(
            ai.djb.agent.core.AgentEvent.Log("فحص المجدول: ${due.size} مهمة مستحقة.")
        )
        Result.success()
    }
}

/** Re-arms everything after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { TaskScheduler.scheduleAll(context) }
        }
    }
}
