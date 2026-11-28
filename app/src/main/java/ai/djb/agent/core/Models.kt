package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** A single function call requested by the model. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
    /** The provider's own argument text; echoed back verbatim so the model sees consistency. */
    val rawArguments: String = arguments.toString(),
)

data class ChatMessage(
    val role: Role,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** JPEG/PNG as raw bytes, sent as an image_url content part when non-empty. */
    val image: ByteArray? = null,
) {
    fun toOpenAi(): JSONObject = JSONObject().apply {
        put("role", role.name.lowercase())
        when {
            image != null && image.isNotEmpty() -> {
                // Multimodal form: text + image in one user message.
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", content)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put(
                            "url",
                            "data:" + imageMime() + ";base64," +
                                android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP)
                        ))
                    })
                })
            }
            role == Role.TOOL -> put("content", content)
            toolCalls.isNotEmpty() -> {
                put("content", content)
                put("tool_calls", JSONArray().apply {
                    toolCalls.forEach { call ->
                        put(JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", call.name)
                                put("arguments", call.rawArguments)
                            })
                        })
                    }
                })
            }
            else -> put("content", content)
        }
        if (role == Role.TOOL) {
            toolCallId?.let { put("tool_call_id", it) }
            toolName?.let { put("name", it) }
        }
    }

    /** Sniffs the container so we send the right data URL even if callers pass PNG bytes. */
    fun imageMime(): String {
        val b = image ?: return "image/png"
        return if (b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
    }

    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)
        fun user(text: String) = ChatMessage(Role.USER, text)
        fun userWithImage(text: String, bytes: ByteArray) =
            ChatMessage(Role.USER, text, image = bytes)
        fun assistant(text: String, calls: List<ToolCall> = emptyList()) =
            ChatMessage(Role.ASSISTANT, text, calls)

        fun tool(callId: String, name: String, result: String) =
            ChatMessage(Role.TOOL, result, toolCallId = callId, toolName = name)
    }
}

/** How dangerous a tool is. Drives the confirmation gate. */
enum class Risk { LOW, MEDIUM, HIGH }

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject = JSONObject(),
    val risk: Risk = Risk.LOW,
) {
    fun toOpenAi(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            // Normalised on the way out: providers validate the schema strictly.
            put("parameters", SchemaSanitizer.sanitize(parameters))
        })
    }
}

/**
 * Some providers (Groq especially) validate every tool schema against the JSON
 * Schema metaschema and reject the whole request with HTTP 400 otherwise. A single
 * property carrying `enum` without `type` — or an object with `properties` but no
 * `type` — is enough to fail. Instead of trusting 40 hand-written schemas, every
 * one of them is normalised here, once, before it goes on the wire.
 */
object SchemaSanitizer {

    fun sanitize(raw: JSONObject): JSONObject {
        val root = deepCopy(raw)
        if (root.optString("type").isBlank()) root.put("type", "object")
        if (root.optString("type") == "object" && !root.has("properties")) {
            root.put("properties", JSONObject())
        }
        root.optJSONObject("properties")?.let { props ->
            val names = props.keys()
            while (names.hasNext()) {
                val key = names.next()
                props.optJSONObject(key)?.let { props.put(key, sanitizeProperty(it)) }
            }
        }
        root.optJSONArray("required")?.let { req ->
            val cleaned = JSONArray()
            for (i in 0 until req.length()) {
                req.optString(i).takeIf { it.isNotBlank() }?.let { cleaned.put(it) }
            }
            root.put("required", cleaned)
        }
        return root
    }

    private fun sanitizeProperty(spec: JSONObject): JSONObject {
        val out = deepCopy(spec)
        val hasEnum = out.has("enum")
        if (out.optString("type").isBlank()) out.put("type", "string")
        if (hasEnum) {
            val values = out.optJSONArray("enum")
            val cleaned = JSONArray()
            if (values != null) {
                for (i in 0 until values.length()) {
                    when (val v = values.opt(i)) {
                        null -> Unit
                        is String -> cleaned.put(v)
                        else -> cleaned.put(v.toString())
                    }
                }
            }
            out.put("enum", cleaned)
        }
        out.optJSONObject("items")?.let { out.put("items", sanitizeProperty(it)) }
        out.optJSONObject("properties")?.let { nested ->
            val names = nested.keys()
            while (names.hasNext()) {
                val key = names.next()
                nested.optJSONObject(key)?.let { nested.put(key, sanitizeProperty(it)) }
            }
        }
        if (out.optString("type") == "object" && !out.has("properties")) {
            out.put("properties", JSONObject())
        }
        return out
    }

    private fun deepCopy(src: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = src.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = src.opt(key)) {
                is JSONObject -> out.put(key, deepCopy(value))
                is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) arr.put(deepCopy(item)) else arr.put(item)
                    }
                    out.put(key, arr)
                }
                null -> Unit
                else -> out.put(key, value)
            }
        }
        return out
    }
}

/**
 * Builds a JSON Schema fragment for one parameter and guarantees it contains a
 * `type`. Prefer this over `listOf(...)` for `enum` fields.
 */
internal fun prop(type: String, description: String? = null, enumValues: List<String>? = null): JSONObject =
    JSONObject().apply {
        put("type", type)
        if (!enumValues.isNullOrEmpty()) {
            put("enum", JSONArray().apply { enumValues.forEach { put(it) } })
        }
        if (!description.isNullOrBlank()) put("description", description)
    }

/** Everything a tool is allowed to touch. No tool gets more than this. */
class ToolContext(
    val app: android.app.Application,
    val store: JsonStore,
    val settings: SettingsStore,
    val permissions: PermissionBroker,
    val bus: EventBus,
    val shell: ShellBridgeRef,
    val adb: AdbBridge,
)

interface AgentTool {
    val spec: ToolSpec
    suspend fun execute(args: JSONObject, ctx: ToolContext): String
}

/**
 * A conditional branch on a skill step.
 *
 * `when_` is matched against the step's result text; `then` steps run in order and
 * may themselves be conditional. This is what turns a linear plan into a plan that
 * can recover ("if the screen didn't change, retry through the other path").
 */
data class SkillBranch(
    val when_: String,
    val contains: Boolean = true,
    val then: List<SkillStep> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("when", when_)
        put("match", if (contains) "contains" else "not_contains")
        put("then", JSONArray().apply { then.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): SkillBranch = SkillBranch(
            when_ = obj.optString("when"),
            contains = obj.optString("match", "contains") != "not_contains",
            then = obj.optJSONArray("then")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { SkillStep.fromJson(it) }
                }
            }.orEmpty(),
        )
    }
}

data class SkillStep(
    val tool: String,
    val args: JSONObject = JSONObject(),
    val saveAs: String? = null,
    /** Retry this step once more if it returns an error. */
    val retry: Int = 0,
    /** Abort the whole skill when this step fails (default true). */
    val stopOnError: Boolean = true,
    val branches: List<SkillBranch> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tool", tool)
        put("args", args)
        saveAs?.let { put("save_as", it) }
        if (retry > 0) put("retry", retry)
        if (!stopOnError) put("stop_on_error", false)
        if (branches.isNotEmpty()) {
            put("branches", JSONArray().apply { branches.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): SkillStep = SkillStep(
            tool = obj.optString("tool"),
            args = obj.optJSONObject("args") ?: JSONObject(),
            saveAs = obj.optString("save_as").takeIf { it.isNotBlank() },
            retry = obj.optInt("retry", 0).coerceIn(0, 3),
            stopOnError = obj.optBoolean("stop_on_error", true),
            branches = obj.optJSONArray("branches")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { SkillBranch.fromJson(it) }
                }
            }.orEmpty(),
        )
    }
}

/** A skill is a named, replayable plan the agent authored itself. */
data class Skill(
    val name: String,
    val description: String,
    val steps: List<SkillStep>,
    val createdAt: Long = System.currentTimeMillis(),
    val runs: Int = 0,
    val successes: Int = 0,
) {
    val reliability: Float
        get() = if (runs == 0) 0f else successes.toFloat() / runs.toFloat()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("created_at", createdAt)
        put("runs", runs)
        put("successes", successes)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): Skill {
            val steps = ArrayList<SkillStep>()
            obj.optJSONArray("steps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { steps.add(SkillStep.fromJson(it)) }
                }
            }
            return Skill(
                name = obj.optString("name"),
                description = obj.optString("description"),
                steps = steps,
                createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                runs = obj.optInt("runs"),
                successes = obj.optInt("successes"),
            )
        }
    }
}
