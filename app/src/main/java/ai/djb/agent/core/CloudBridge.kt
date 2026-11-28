package ai.djb.agent.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The phone's link to the cloud worker.
 *
 * This is the "leaving the phone" half that the internet tools cannot cover: a task that
 * keeps running while the app is closed, and a snapshot of what the agent learned that
 * survives a reinstall. The token lives in settings and is sent as a bearer header — it
 * is the user's own secret, never logged, never exported.
 *
 * The design keeps the phone sovereign: the worker executes *text* tasks, never device
 * actions. Device control stays local, so a compromised server cannot touch the phone.
 */
class CloudBridge(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()

    data class Health(val ok: Boolean, val storage: String, val modelConfigured: Boolean, val version: String)

    data class TaskResult(val ok: Boolean, val answer: String, val degraded: Boolean, val id: String)

    fun isConfigured(): Boolean = settings.cloudUrl.isNotBlank() && settings.cloudToken.isNotBlank()

    private fun endpoint(path: String): String =
        settings.cloudUrl.trimEnd('/') + path

    private fun request(path: String): Request.Builder =
        Request.Builder()
            .url(endpoint(path))
            .header("Authorization", "Bearer ${settings.cloudToken}")
            .header("Content-Type", "application/json")

    /** Health probe. Returns null when unreachable — the UI shows which, never guesses. */
    fun health(): Health? = runCatching {
        val req = Request.Builder().url(endpoint("/health")).get().build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            val json = JSONObject(body)
            Health(
                ok = res.isSuccessful && json.optBoolean("ok"),
                storage = json.optString("storage", "?"),
                modelConfigured = json.optBoolean("model_configured"),
                version = json.optString("service", "djb-ai-worker"),
            )
        }
    }.getOrNull()

    /**
     * Sends a task to the worker. `allowNet` is passed through so the worker's behaviour
     * matches what the user allowed here.
     */
    fun runTask(task: String, allowNet: Boolean = true): TaskResult {
        if (!isConfigured()) {
            return TaskResult(false, "السحابة غير مضبوطة: أدخل الرابط والرمز في الإعدادات.", true, "")
        }
        val payload = JSONObject().apply {
            put("task", task)
            put("allow_net", allowNet)
        }.toString()

        return try {
            http.newCall(
                request("/task").post(payload.toRequestBody("application/json".toMediaType())).build()
            ).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val hint = when (res.code) {
                        401 -> "الرمز مرفوض — تحقّق من WORKER_TOKEN."
                        404 -> "المسار غير موجود — تحقّق من الرابط."
                        500 -> "خطأ في العامل السحابي."
                        else -> "HTTP ${res.code}"
                    }
                    return TaskResult(false, "$hint\n${body.take(200)}", true, "")
                }
                val json = JSONObject(body)
                TaskResult(
                    ok = !json.optBoolean("degraded"),
                    answer = json.optString("answer"),
                    degraded = json.optBoolean("degraded"),
                    id = json.optString("id"),
                )
            }
        } catch (e: Exception) {
            TaskResult(false, "تعذّر الوصول للعامل: ${e.message}", true, "")
        }
    }

    /** Pushes what the agent learned so another device (or a fresh install) inherits it. */
    fun pushSnapshot(client: String, skills: List<JSONObject>, lessons: List<String>, memory: Map<String, String>): Boolean {
        if (!isConfigured()) return false
        val payload = JSONObject().apply {
            put("client", client)
            put("skills", JSONArray().apply { skills.forEach { put(it) } })
            put("lessons", JSONArray().apply { lessons.forEach { put(it) } })
            put("memory", JSONObject().apply { memory.forEach { (k, v) -> put(k, v) } })
        }.toString()

        return runCatching {
            http.newCall(
                request("/sync").post(payload.toRequestBody("application/json".toMediaType())).build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Pulls the last snapshot; empty object when there is none. */
    fun pullSnapshot(client: String): JSONObject? {
        if (!isConfigured()) return null
        return runCatching {
            http.newCall(request("/sync?client=$client").get().build()).execute().use { res ->
                if (!res.isSuccessful) return null
                JSONObject(res.body?.string().orEmpty())
            }
        }.getOrNull()
    }
}
