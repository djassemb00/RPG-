package ai.djb.agent.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore of everything the agent has learned.
 *
 * The valuable part of this app is not the code — it is the skills it wrote, the
 * memory it accumulated, the rules the user taught it, and the provider chain that
 * took time to tune. A reinstall would throw all of that away, so it all lives in
 * plain JSON that can be exported, read, hand-edited, and imported elsewhere.
 *
 * Two deliberate rules:
 *  1. **API keys are opt-in.** A backup is a file users mail to themselves, so keys
 *     are stripped unless explicitly requested.
 *  2. **Import is a merge, not a wipe.** Skills arrive as an upsert; memory keys merge;
 *     settings only overwrite what the file actually contains. Nothing is deleted by
 *     accident.
 */
class BackupManager(
    private val context: Context,
    private val store: JsonStore,
    private val settings: SettingsStore,
    private val providers: ProviderRegistry,
    private val tasks: TaskStore,
    private val adb: AdbBridge,
) {

    companion object {
        const val FORMAT = "djb-ai-backup"
        const val VERSION = 1
        private const val MIME = "application/json"

        /** Files that hold learned state rather than secrets. */
        private val STATE_FILES = listOf("skills.json", "memory.json", "rules.json", "tasks.json")
    }

    data class Result(val ok: Boolean, val message: String)

    data class Summary(
        val skills: Int,
        val memory: Int,
        val rules: Int,
        val tasks: Int,
        val providers: Int,
        val createdAt: String,
        val hasKeys: Boolean,
    )

    /** Builds the backup document. `includeKeys` must be an explicit choice. */
    fun export(includeKeys: Boolean = false): JSONObject {
        val skills = store.readSkills()
        val memory = store.readMemory()
        val rules = store.readRules()
        val scheduled = tasks.load()

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("created_at", System.currentTimeMillis())
            put("app", "ai.djb.agent")

            put("skills", JSONArray().apply { skills.forEach { put(it.toJson()) } })
            put("memory", JSONObject().apply { memory.forEach { (k, v) -> put(k, v) } })
            put("rules", JSONArray().apply { rules.forEach { put(it) } })
            put("tasks", JSONArray().apply { scheduled.forEach { put(it.toJson()) } })

            put("providers", JSONArray().apply {
                providers.load().forEach { slot ->
                    val obj = slot.toJson()
                    if (!includeKeys) obj.put("key", "")
                    put(obj)
                }
            })

            // Behaviour settings only — never the raw key, which lives with providers.
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

            // Cloud worker URL only — never the shared token, for the same reason the
            // ADB private key stays behind: a backup file is meant to be shareable.
            // Repository name only; the GitHub token is a write credential and is
            // excluded exactly like the API keys and the ADB private key.
            put("github", JSONObject().apply {
                if (settings.githubRepo.isNotBlank()) {
                    put("repo", settings.githubRepo)
                    put("branch", settings.githubBranch)
                }
            })

            put("cloud", JSONObject().apply {
                if (settings.cloudUrl.isNotBlank()) {
                    put("url", settings.cloudUrl)
                    put("client", settings.cloudClientId)
                }
            })

            // ADB host/port only: the private key never leaves the device.
            put("adb", JSONObject().apply {
                put("host", adb.config().host)
                put("port", adb.config().port)
                put("auto_connect", adb.config().autoConnect)
            })
        }
    }

    fun summaryOf(doc: JSONObject): Summary = Summary(
        skills = doc.optJSONArray("skills")?.length() ?: 0,
        memory = doc.optJSONObject("memory")?.length() ?: 0,
        rules = doc.optJSONArray("rules")?.length() ?: 0,
        tasks = doc.optJSONArray("tasks")?.length() ?: 0,
        providers = doc.optJSONArray("providers")?.length() ?: 0,
        createdAt = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(doc.optLong("created_at")))
        }.getOrDefault("—"),
        hasKeys = (doc.optJSONArray("providers")?.let { arr ->
            (0 until arr.length()).any { arr.optJSONObject(it)?.optString("key").orEmpty().isNotBlank() }
        } ?: false),
    )

    /** Writes the backup to a user-chosen location (Storage Access Framework). */
    fun exportTo(uri: Uri, includeKeys: Boolean): Result {
        return try {
            val text = export(includeKeys).toString(2)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray())
            } ?: return Result(false, "تعذّر فتح الملف للكتابة.")
            Result(true, "تم التصدير (${text.length / 1024} كيلوبايت).")
        } catch (e: Exception) {
            Result(false, "فشل التصدير: ${e.message}")
        }
    }

    /** Convenience path for the file the app shares directly. */
    fun exportToCache(includeKeys: Boolean): File {
        val dir = File(context.cacheDir, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "djb-ai-backup-$stamp.json")
        file.writeText(export(includeKeys).toString(2))
        return file
    }

    fun readFrom(uri: Uri): JSONObject? = try {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        text?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Merges a backup into current state. Returns a human-readable tally so the user
     * can see exactly what changed instead of a vague "done".
     */
    fun import(doc: JSONObject): Result {
        if (doc.optString("format") != FORMAT) {
            return Result(false, "هذا ليس ملف نسخة احتياطية من djb ai.")
        }
        val version = doc.optInt("version", 0)
        if (version > VERSION) {
            return Result(false, "الملف من إصدار أحدث ($version). حدّث التطبيق أولًا.")
        }

        val notes = ArrayList<String>()

        // Skills: upsert by name, newest definition wins.
        doc.optJSONArray("skills")?.let { arr ->
            val incoming = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Skill::fromJson) }
            if (incoming.isNotEmpty()) {
                val current = store.readSkills().toMutableList()
                var added = 0
                var updated = 0
                incoming.forEach { skill ->
                    val idx = current.indexOfFirst { it.name == skill.name }
                    if (idx >= 0) {
                        current[idx] = skill
                        updated++
                    } else {
                        current.add(skill)
                        added++
                    }
                }
                store.writeSkills(current)
                notes.add("المهارات: +$added، محدّثة $updated")
            }
        }

        doc.optJSONObject("memory")?.let { obj ->
            if (obj.length() > 0) {
                val memory = store.readMemory()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    memory[k] = obj.optString(k)
                }
                store.writeMemory(memory)
                notes.add("الذاكرة: ${obj.length()} مفتاحًا")
            }
        }

        doc.optJSONArray("rules")?.let { arr ->
            val incoming = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { r -> r.isNotBlank() } }
            if (incoming.isNotEmpty()) {
                val merged = (store.readRules() + incoming).distinct()
                store.writeRules(merged)
                notes.add("القواعد: ${incoming.size}")
            }
        }

        doc.optJSONArray("tasks")?.let { arr ->
            val incoming = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(ScheduledTask::fromJson) }
            if (incoming.isNotEmpty()) {
                val current = tasks.load().toMutableList()
                incoming.forEach { task ->
                    if (current.none { it.id == task.id }) current.add(task)
                }
                tasks.save(current)
                notes.add("المهام: ${incoming.size} (بدون تكرار)")
            }
        }

        doc.optJSONArray("providers")?.let { arr ->
            val incoming = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(ProviderSlot::fromJson) }
            if (incoming.isNotEmpty()) {
                val current = providers.load().toMutableList()
                var needsKey = 0
                incoming.forEach { slot ->
                    val idx = current.indexOfFirst { it.presetId == slot.presetId }
                    val merged = if (idx >= 0) {
                        // An empty key in the file must not erase a working local key.
                        val local = current[idx]
                        slot.copy(key = slot.key.ifBlank { local.key })
                    } else {
                        slot
                    }
                    if (merged.key.isBlank()) needsKey++
                    if (idx >= 0) current[idx] = merged else current.add(merged)
                }
                providers.save(current)
                notes.add(
                    "المزوّدون: ${incoming.size}" +
                        if (needsKey > 0) " — $needsKey بدون مفتاح، أدخل مفاتيحها" else ""
                )
            }
        }

        doc.optJSONObject("settings")?.let { s ->
            if (s.has("agent_name")) settings.agentName = s.optString("agent_name")
            if (s.has("model")) settings.model = s.optString("model")
            if (s.has("api_base_url")) settings.apiBaseUrl = s.optString("api_base_url")
            if (s.has("max_steps")) settings.maxSteps = s.optInt("max_steps", settings.maxSteps)
            if (s.has("temperature")) settings.temperature = s.optDouble("temperature", settings.temperature)
            if (s.has("autonomy")) {
                runCatching { Autonomy.valueOf(s.optString("autonomy")) }
                    .onSuccess { settings.autonomy = it }
            }
            if (s.has("self_improve")) settings.selfImprove = s.optBoolean("self_improve")
            if (s.has("vision_enabled")) settings.visionEnabled = s.optBoolean("vision_enabled")
            if (s.has("show_routing")) settings.showRouting = s.optBoolean("show_routing")
            if (s.has("extra_instruction")) settings.extraInstruction = s.optString("extra_instruction")
            notes.add("الإعدادات")
        }

        doc.optJSONObject("adb")?.let { a ->
            val port = a.optInt("port", 0)
            if (port > 0) {
                adb.saveConfig(
                    adb.config().copy(
                        host = a.optString("host", adb.config().host).ifBlank { adb.config().host },
                        port = port,
                        autoConnect = a.optBoolean("auto_connect", adb.config().autoConnect),
                    )
                )
                notes.add("إعداد ADB (المفتاح الخاص لا يُنقل — أعد الإقران)")
            }
        }

        if (notes.isEmpty()) return Result(false, "الملف لا يحتوي بيانات قابلة للاستيراد.")
        return Result(true, "تم الاستيراد: " + notes.joinToString(" · "))
    }
}
