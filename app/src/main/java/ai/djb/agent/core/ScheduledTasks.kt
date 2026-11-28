package ai.djb.agent.core

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/** How often a scheduled task repeats. */
enum class Repeat {
    ONCE,
    HOURLY,
    DAILY,
    WEEKLY,
    ;

    fun label(): String = when (this) {
        ONCE -> "مرة واحدة"
        HOURLY -> "كل ساعة"
        DAILY -> "يوميًا"
        WEEKLY -> "أسبوعيًا"
    }
}

/** What to do when a scheduled run fails (or needs a tap the user isn't there for). */
enum class OnFailure {
    SKIP,
    RETRY_ONCE,
    NOTIFY,
}

data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    val hour: Int = 9,
    val minute: Int = 0,
    val repeat: Repeat = Repeat.DAILY,
    val enabled: Boolean = true,
    val onFailure: OnFailure = OnFailure.NOTIFY,
    /** Hourly tasks fire at this minute offset so they don't all align. */
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0,
    val lastResult: String = "",
    val lastOk: Boolean = false,
    val nextRunAt: Long = 0,
    val runCount: Int = 0,
    val failCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("prompt", prompt)
        put("hour", hour)
        put("minute", minute)
        put("repeat", repeat.name)
        put("enabled", enabled)
        put("on_failure", onFailure.name)
        put("created_at", createdAt)
        put("last_run_at", lastRunAt)
        put("last_result", lastResult)
        put("last_ok", lastOk)
        put("next_run_at", nextRunAt)
        put("run_count", runCount)
        put("fail_count", failCount)
    }

    /** The next wall-clock time this task should fire, computed from `from`. */
    fun computeNextRun(from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        when (repeat) {
            Repeat.ONCE -> {
                if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
            Repeat.DAILY -> {
                if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
            Repeat.WEEKLY -> {
                while (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 7)
                return cal.timeInMillis
            }
            Repeat.HOURLY -> {
                // Fire at :minute of every hour.
                val now = Calendar.getInstance().apply {
                    timeInMillis = from
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (now.timeInMillis <= from) now.add(Calendar.HOUR_OF_DAY, 1)
                return now.timeInMillis
            }
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): ScheduledTask = ScheduledTask(
            id = obj.optString("id"),
            title = obj.optString("title"),
            prompt = obj.optString("prompt"),
            hour = obj.optInt("hour", 9),
            minute = obj.optInt("minute", 0),
            repeat = runCatching { Repeat.valueOf(obj.optString("repeat", "DAILY")) }.getOrDefault(Repeat.DAILY),
            enabled = obj.optBoolean("enabled", true),
            onFailure = runCatching { OnFailure.valueOf(obj.optString("on_failure", "NOTIFY")) }
                .getOrDefault(OnFailure.NOTIFY),
            createdAt = obj.optLong("created_at", System.currentTimeMillis()),
            lastRunAt = obj.optLong("last_run_at"),
            lastResult = obj.optString("last_result"),
            lastOk = obj.optBoolean("last_ok"),
            nextRunAt = obj.optLong("next_run_at"),
            runCount = obj.optInt("run_count"),
            failCount = obj.optInt("fail_count"),
        )
    }
}

/** Reads and writes the scheduled task list. */
class TaskStore(private val store: JsonStore) {

    private val file = "tasks.json"

    fun load(): List<ScheduledTask> {
        val arr = store.readArray(file)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(ScheduledTask::fromJson) }
    }

    fun save(tasks: List<ScheduledTask>) {
        store.writeArray(file, JSONArray().apply { tasks.forEach { put(it.toJson()) } })
    }

    fun upsert(task: ScheduledTask): List<ScheduledTask> {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == task.id }
        if (idx >= 0) current[idx] = task else current.add(task)
        save(current)
        return load()
    }

    fun remove(id: String): List<ScheduledTask> {
        save(load().filterNot { it.id == id })
        return load()
    }

    fun toggle(id: String, enabled: Boolean): List<ScheduledTask> =
        load().map { if (it.id == id) it.copy(enabled = enabled) else it }.also { save(it) }

    fun due(now: Long = System.currentTimeMillis()): List<ScheduledTask> =
        load().filter { it.enabled && it.nextRunAt in 1 until now }

    fun rearmAll() {
        val now = System.currentTimeMillis()
        save(load().map { task ->
            if (task.nextRunAt == 0L) task.copy(nextRunAt = task.computeNextRun(now)) else task
        })
    }
}
