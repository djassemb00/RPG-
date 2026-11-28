package ai.djb.agent.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The self-build door.
 *
 * A compiled APK cannot rewrite itself, but it *can* ask a build farm to produce the next
 * version — which is exactly how the agent gains a capability it doesn't have today. The
 * flow is deliberately slow and observable:
 *
 *   1. the phone stages file contents (validated twice: here and in the workflow),
 *   2. the workflow applies them, builds, and publishes **only if green**,
 *   3. the phone polls the run and reads the real compiler errors on failure.
 *
 * Point 3 matters: the agent must be able to say "the build failed because line 42
 * references X", not "something went wrong". A build loop that can't read its own errors
 * is a slot machine.
 */
class GitHubBridge(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Run(val id: Long, val status: String, val conclusion: String?, val url: String) {
        val finished: Boolean get() = status == "completed"
        val ok: Boolean get() = conclusion == "success"
    }

    data class Report(val ok: Boolean, val errors: List<String>, val raw: String)

    fun isConfigured(): Boolean =
        settings.githubToken.isNotBlank() && settings.githubRepo.contains('/')

    private fun api(path: String): String = "https://api.github.com/repos/${settings.githubRepo}$path"

    private fun builder(path: String): Request.Builder =
        Request.Builder()
            .url(api(path))
            .header("Authorization", "Bearer ${settings.githubToken}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

    /** Confirms the token works and names the account it belongs to. */
    fun whoAmI(): String? = runCatching {
        http.newCall(
            Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer ${settings.githubToken}")
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute().use { res ->
            if (!res.isSuccessful) null
            else JSONObject(res.body?.string().orEmpty()).optString("login").ifBlank { null }
        }
    }.getOrNull()

    /**
     * Dispatches the self-build. `files` maps a repo path to its full new content.
     * Returns the run id to poll, or an error string.
     */
    fun dispatchSelfBuild(files: Map<String, String>, reason: String): Result<Long> {
        if (!isConfigured()) {
            return Result.failure(IllegalStateException("GitHub غير مضبوط: التوكن والمستودع مطلوبان."))
        }
        if (files.isEmpty()) return Result.failure(IllegalArgumentException("لا ملفات للتعديل."))

        val payload = JSONObject().apply {
            put("ref", settings.githubBranch)
            put("inputs", JSONObject().apply {
                put("reason", reason.take(200))
                put("bump", "auto")
                put("files", JSONArray().apply {
                    files.forEach { (path, content) ->
                        put(JSONObject().apply {
                            put("path", path)
                            put("content_base64", android.util.Base64.encodeToString(
                                content.toByteArray(), android.util.Base64.NO_WRAP
                            ))
                        })
                    }
                }.toString())
            })
        }.toString()

        return try {
            http.newCall(
                builder("/actions/workflows/${settings.githubWorkflow}/dispatches")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { res ->
                if (!res.isSuccessful) {
                    val body = res.body?.string().orEmpty().take(300)
                    val hint = when (res.code) {
                        401 -> "التوكن مرفوض."
                        403 -> "التوكن بلا صلاحية Actions/Workflows."
                        404 -> "المستودع أو ملف الـ workflow غير موجود."
                        else -> "HTTP ${res.code}"
                    }
                    return Result.failure(IllegalStateException("$hint $body"))
                }
                // 204 means accepted; the run id arrives on the next poll.
                Result.success(0L)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Most recent self-build run, so the phone can poll without knowing the id. */
    fun latestRun(workflow: String = settings.githubWorkflow): Run? = runCatching {
        http.newCall(
            builder("/actions/workflows/$workflow/runs?per_page=1")
                .get()
                .build()
        ).execute().use { res ->
            if (!res.isSuccessful) return null
            val runs = JSONObject(res.body?.string().orEmpty()).optJSONArray("workflow_runs")
                ?: return null
            val first = runs.optJSONObject(0) ?: return null
            Run(
                id = first.optLong("id"),
                status = first.optString("status"),
                conclusion = first.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
                url = first.optString("html_url"),
            )
        }
    }.getOrNull()

    /**
     * Reads the workflow's own committed report. CI logs aren't reachable from a phone,
     * so the report is written into the repository where the agent can always read it —
     * the same trick that made the build loop debuggable during development.
     */
    fun readReport(): Report? = runCatching {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/${settings.githubRepo}/${settings.githubBranch}/SELF_BUILD_REPORT.md")
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return null
            val text = res.body?.string().orEmpty()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = JSONObject(text.substring(start, end + 1))
            val errors = json.optJSONArray("errors")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { e -> e.isNotBlank() } }
            }.orEmpty()
            Report(json.optBoolean("ok"), errors, text)
        }
    }.getOrNull()
}
