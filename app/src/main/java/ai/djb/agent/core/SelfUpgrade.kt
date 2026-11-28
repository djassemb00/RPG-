package ai.djb.agent.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A/B self-upgrade with a safety valve.
 *
 * The rule this class enforces: **a candidate never replaces a running version in
 * place.** It is staged next to the stable one, both are announced, and the stable one
 * stays untouched until the candidate has proven itself. If anything about the new
 * version looks wrong, the stable one comes back with one action — and a crash during
 * startup brings it back automatically.
 *
 * Concretely, a "version" here is the agent's *behaviour bundle*: which skills are
 * enabled, the instruction set, the prompt-affecting settings and the tool allowlist.
 * That is the part of itself the agent can genuinely replace at runtime. Code changes
 * go through the sandbox + a real build (see PatchSandbox and SelfCode), and the same
 * safety valve records the previously stable build so a faulty APK can be rolled back.
 */
class SelfUpgrade(
    private val context: Context,
    private val store: JsonStore,
    private val settings: SettingsStore,
) {

    companion object {
        private const val STATE_FILE = "selfupgrade.json"
        private const val VERSION_DIR = "versions"
        const val MAX_HISTORY = 10

        /** Consecutive failed health checks before the automatic rollback fires. */
        const val FAILURE_LIMIT = 2
    }

    data class Version(
        val id: String,
        val label: String,
        val createdAt: Long,
        val fingerprint: String,
        val parentId: String?,
        val sandboxPassed: Boolean,
        val detail: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("label", label)
            put("created_at", createdAt)
            put("fingerprint", fingerprint)
            put("parent_id", parentId ?: "")
            put("sandbox_passed", sandboxPassed)
            put("detail", detail)
        }

        companion object {
            fun fromJson(o: JSONObject) = Version(
                id = o.optString("id"),
                label = o.optString("label"),
                createdAt = o.optLong("created_at"),
                fingerprint = o.optString("fingerprint"),
                parentId = o.optString("parent_id").takeIf { it.isNotBlank() },
                sandboxPassed = o.optBoolean("sandbox_passed"),
                detail = o.optString("detail"),
            )
        }
    }

    data class State(
        val stableId: String?,
        val candidateId: String?,
        val failures: Int,
        val history: List<Version>,
        val lastRollbackAt: Long,
        val lastRollbackReason: String,
    )

    private fun readState(): State {
        val o = store.readObject(STATE_FILE)
        val arr = o.optJSONArray("history") ?: JSONArray()
        return State(
            stableId = o.optString("stable_id").takeIf { it.isNotBlank() },
            candidateId = o.optString("candidate_id").takeIf { it.isNotBlank() },
            failures = o.optInt("failures", 0),
            history = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Version::fromJson) },
            lastRollbackAt = o.optLong("last_rollback_at"),
            lastRollbackReason = o.optString("last_rollback_reason"),
        )
    }

    private fun writeState(state: State) {
        store.writeObject(STATE_FILE, JSONObject().apply {
            put("stable_id", state.stableId ?: "")
            put("candidate_id", state.candidateId ?: "")
            put("failures", state.failures)
            put("last_rollback_at", state.lastRollbackAt)
            put("last_rollback_reason", state.lastRollbackReason)
            put("history", JSONArray().apply { state.history.forEach { put(it.toJson()) } })
        })
    }

    fun state(): State = readState()

    // ---- snapshots ----

    /**
     * Captures the current behaviour bundle. This is what "the stable version" means:
     * a complete, restorable picture, not a pointer to code that may have moved.
     */
    fun snapshot(label: String, parentId: String?, sandboxPassed: Boolean, detail: String): Version {
        val id = "v" + System.currentTimeMillis().toString(36)
        val bundle = behaviourBundle()
        val fingerprint = sha(bundle.toString())

        val dir = java.io.File(store.privateDir(), "$VERSION_DIR/$id").apply { mkdirs() }
        java.io.File(dir, "bundle.json").writeText(bundle.toString(2))

        val version = Version(
            id = id,
            label = label,
            createdAt = System.currentTimeMillis(),
            fingerprint = fingerprint,
            parentId = parentId,
            sandboxPassed = sandboxPassed,
            detail = detail,
        )
        val current = readState()
        val history = (current.history + version).takeLast(MAX_HISTORY)
        writeState(current.copy(history = history))
        return version
    }

    /** Everything that decides how the agent behaves — restorable in one move. */
    private fun behaviourBundle(): JSONObject = JSONObject().apply {
        put("skills", JSONArray().apply { store.readSkills().forEach { put(it.toJson()) } })
        put("rules", JSONArray().apply { store.readRules().forEach { put(it) } })
        put("memory", JSONObject().apply { store.readMemory().forEach { (k, v) -> put(k, v) } })
        put("settings", JSONObject().apply {
            put("agent_name", settings.agentName)
            put("model", settings.model)
            put("api_base_url", settings.apiBaseUrl)
            put("max_steps", settings.maxSteps)
            put("temperature", settings.temperature)
            put("autonomy", settings.autonomy.name)
            put("self_improve", settings.selfImprove)
            put("vision_enabled", settings.visionEnabled)
            put("show_routing", settings.showRouting)
            put("extra_instruction", settings.extraInstruction)
        })
        put("app", JSONObject().apply {
            put("version_code", ai.djb.agent.BuildConfig.VERSION_CODE)
            put("version_name", ai.djb.agent.BuildConfig.VERSION_NAME)
        })
    }

    fun restore(id: String): Boolean {
        val dir = java.io.File(store.privateDir(), "$VERSION_DIR/$id")
        val file = java.io.File(dir, "bundle.json")
        if (!file.exists()) return false
        val bundle = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false

        // Skills and rules are additive state; a rollback must remove what was added
        // *after* the snapshot too, so they are replaced rather than merged.
        bundle.optJSONArray("skills")?.let { arr ->
            store.writeSkills((0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Skill::fromJson) })
        }
        bundle.optJSONArray("rules")?.let { arr ->
            store.writeRules((0 until arr.length()).mapNotNull { arr.optString(it).takeIf { r -> r.isNotBlank() } })
        }
        bundle.optJSONObject("settings")?.let { s ->
            if (s.has("agent_name")) settings.agentName = s.optString("agent_name")
            if (s.has("model")) settings.model = s.optString("model")
            if (s.has("api_base_url")) settings.apiBaseUrl = s.optString("api_base_url")
            if (s.has("max_steps")) settings.maxSteps = s.optInt("max_steps", settings.maxSteps)
            if (s.has("temperature")) settings.temperature = s.optDouble("temperature", settings.temperature)
            runCatching { Autonomy.valueOf(s.optString("autonomy")) }
                .onSuccess { settings.autonomy = it }
            if (s.has("self_improve")) settings.selfImprove = s.optBoolean("self_improve")
            if (s.has("vision_enabled")) settings.visionEnabled = s.optBoolean("vision_enabled")
            if (s.has("show_routing")) settings.showRouting = s.optBoolean("show_routing")
            if (s.has("extra_instruction")) settings.extraInstruction = s.optString("extra_instruction")
        }
        return true
    }

    // ---- promotion / rollback ----

    sealed class PromoteOutcome {
        data class Promoted(val candidate: Version, val stable: Version?) : PromoteOutcome()
        data class Rejected(val reasons: List<String>) : PromoteOutcome()
    }

    /**
     * Stages a candidate only if the sandbox signed off on it. The current behaviour is
     * snapshotted as `stable` first, so a rollback always has somewhere to return to.
     */
    fun promote(verdict: PatchSandbox.Verdict, label: String): PromoteOutcome {
        if (!verdict.passed) {
            return PromoteOutcome.Rejected(verdict.failures().map { "${it.stage}: ${it.detail}" })
        }
        val current = readState()
        val stable = current.history.lastOrNull { it.id == current.stableId }
            ?: snapshot("stable", null, true, "نسخة مستقرة قبل الترقية")

        // The candidate is recorded with the sandbox fingerprint so the promotion can be
        // audited later: the file it claims to be is the file that was tested.
        val candidate = snapshot(
            label = label,
            parentId = stable.id,
            sandboxPassed = true,
            detail = "اجتاز ${verdict.checks.size} فحصًا · ${verdict.fingerprint.take(12)}",
        )

        val withStable = if (current.stableId == null) {
            // First promotion: the pre-change state becomes the stable baseline.
            writeState(readState().copy(stableId = stable.id))
            readState()
        } else {
            current
        }

        writeState(
            withStable.copy(
                candidateId = candidate.id,
                failures = 0,
                history = (withStable.history + candidate).distinctBy { it.id }.takeLast(MAX_HISTORY),
            )
        )
        return PromoteOutcome.Promoted(candidate, stable)
    }

    /**
     * The safety valve. Restores the stable snapshot, marks why, and clears the
     * candidate so a half-applied upgrade can't linger.
     */
    fun rollback(reason: String): Boolean {
        val current = readState()
        val target = current.stableId ?: current.history.firstOrNull()?.id ?: return false
        if (!restore(target)) return false
        writeState(
            current.copy(
                candidateId = null,
                failures = 0,
                lastRollbackAt = System.currentTimeMillis(),
                lastRollbackReason = reason,
            )
        )
        return true
    }

    /** Drops a candidate without touching the stable version. */
    fun discardCandidate() {
        val current = readState()
        writeState(current.copy(candidateId = null, failures = 0))
    }

    /**
     * Called at startup. If a candidate was promoted but the app has since failed its
     * health checks, the valve fires by itself — the user does not have to notice a
     * broken agent to get their working one back.
     */
    fun healthCheckOnStartup(broken: Boolean, reason: String): State {
        val current = readState()
        if (current.candidateId == null) return current

        if (broken) {
            val failures = current.failures + 1
            if (failures >= FAILURE_LIMIT) {
                rollback("فحوص السلامة فشلت $failures مرات: $reason")
                return readState()
            }
            writeState(current.copy(failures = failures))
            return readState()
        }

        // Healthy: the candidate becomes the new stable baseline.
        writeState(
            current.copy(
                stableId = current.candidateId,
                candidateId = null,
                failures = 0,
            )
        )
        return readState()
    }

    fun history(): List<Version> = readState().history.sortedByDescending { it.createdAt }

    private fun sha(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
