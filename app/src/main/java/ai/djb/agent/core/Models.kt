package ai.djb.agent.core

import org.json.JSONArray
import org.json.JSONObject

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** A single function call requested by the model. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
)

data class ChatMessage(
    val role: Role,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
) {
    fun toOpenAi(): JSONObject = JSONObject().apply {
        put("role", role.name.lowercase())
        when {
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
                                put("arguments", call.arguments.toString())
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

    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)
        fun user(text: String) = ChatMessage(Role.USER, text)
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
    val parameters: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()),
    val risk: Risk = Risk.LOW,
) {
    fun toOpenAi(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }
}

/** Everything a tool is allowed to touch. No tool gets more than this. */
class ToolContext(
    val app: android.app.Application,
    val store: JsonStore,
    val settings: SettingsStore,
    val permissions: PermissionBroker,
    val bus: EventBus,
    val shell: ShellBridgeRef,
)

interface AgentTool {
    val spec: ToolSpec
    suspend fun execute(args: JSONObject, ctx: ToolContext): String
}

data class SkillStep(
    val tool: String,
    val args: JSONObject = JSONObject(),
    val saveAs: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tool", tool)
        put("args", args)
        saveAs?.let { put("save_as", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject): SkillStep = SkillStep(
            tool = obj.optString("tool"),
            args = obj.optJSONObject("args") ?: JSONObject(),
            saveAs = obj.optString("save_as").takeIf { it.isNotBlank() },
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
