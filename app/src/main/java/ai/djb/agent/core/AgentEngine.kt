package ai.djb.agent.core

import ai.djb.agent.services.AgentAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent loop: prompt -> model -> tool calls -> results -> repeat.
 *
 * One run at a time (a Mutex), a hard step ceiling, and every decision logged so
 * the user can audit exactly what the agent did on their device.
 */
class AgentEngine(
    private val app: android.app.Application,
    private val store: JsonStore,
    private val settings: SettingsStore,
    private val bus: EventBus,
    private val registry: ToolRegistry,
    private val client: LlmClient,
    private val permissions: PermissionBroker,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runLock = Mutex()
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    val isRunning: Boolean get() = running.get()

    fun submit(task: String) {
        job = scope.launch {
            runLock.withLock {
                running.set(true)
                bus.emit(AgentEvent.RunningChanged(true))
                try {
                    runInternal(task)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    bus.emit(AgentEvent.Log("تم إيقاف المهمة بناءً على طلبك.", LogLevel.WARN))
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    bus.emit(AgentEvent.Log("توقف الوكيل: $msg", LogLevel.ERROR))
                    store.appendLog(logLine("error", msg))
                } finally {
                    running.set(false)
                    bus.emit(AgentEvent.RunningChanged(false))
                }
            }
        }
    }

    fun cancel() {
        permissions.cancelAll()
        AgentAccessibilityService.instance()?.clearConversation()
        job?.cancel()
        running.set(false)
    }

    private suspend fun runInternal(task: String) {
        if (!client.hasUsableProvider()) {
            bus.emit(
                AgentEvent.Log(
                    "لا يوجد مزوّد مضبوط: أضف مفتاحًا واحدًا على الأقل في الإعدادات ← المزوّدون.",
                    LogLevel.ERROR,
                )
            )
            bus.emit(AgentEvent.RunningChanged(false))
            return
        }

        val accessibilityOn = AgentAccessibilityService.instance() != null
        val skills = store.readSkills()

        val systemPrompt = PromptBuilder.build(
            settings = settings,
            store = store,
            toolNames = registry.specs().map { it.name },
            installedSkillNames = skills.map { it.name },
            accessibilityOn = accessibilityOn,
        )

        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage.system(systemPrompt)
        messages += restoreShortTermMemory()
        messages += ChatMessage.user(
            task + PromptBuilder.deviceContext(accessibilityOn, shellCapability())
        )

        bus.emit(AgentEvent.Log("المهمة: $task"))

        var step = 0
        val maxSteps = settings.maxSteps

        while (step < maxSteps) {
            if (job?.isCancelled == true) return
            step++

            val response = try {
                client.chat(messages, registry.specs())
            } catch (e: Exception) {
                bus.emit(AgentEvent.Log("فشل الاتصال بالموديل: ${e.message}", LogLevel.ERROR))
                store.appendLog(logLine("llm_error", e.message.orEmpty()))
                return
            }

            if (response.content.isNotBlank()) {
                bus.emit(AgentEvent.Log(response.content))
            }

            if (response.toolCalls.isEmpty()) {
                bus.emit(AgentEvent.Answer(response.content))
                messages += ChatMessage.assistant(response.content)
                persistTurn(task, response.content, messages)
                return
            }

            messages += ChatMessage.assistant(response.content, response.toolCalls)

            for (call in response.toolCalls) {
                if (job?.isCancelled == true) return
                bus.emit(AgentEvent.ToolStarted(call.name, call.arguments.toString()))
                store.appendLog(logLine("tool_start", "${call.name} ${call.arguments}"))

                val result = registry.invoke(call.name, call.arguments, settings.autonomy) { name, risk ->
                    permissions.approve(name, call.arguments.toString(), risk, settings.autonomy)
                }

                bus.emit(AgentEvent.ToolFinished(call.name, result))
                store.appendLog(logLine("tool_end", "${call.name} -> ${result.take(300)}"))
                messages += ChatMessage.tool(call.id, call.name, result.take(4000))
            }
        }

        val note = "وصلت للحد الأقصى من الخطوات ($maxSteps). المهمة قد تكون غير مكتملة."
        bus.emit(AgentEvent.Log(note, LogLevel.WARN))
        bus.emit(AgentEvent.Answer(note))
    }

    private fun shellCapability(): String = runCatching {
        ai.djb.agent.tools.ShellBridge(this.app).capability().name
    }.getOrDefault("UNKNOWN")

    private fun restoreShortTermMemory(): List<ChatMessage> {
        val arr = store.readArray("turns.json")
        if (arr.length() == 0) return emptyList()
        val out = ArrayList<ChatMessage>()
        // Keep the last two exchanges for continuity without blowing the context window.
        for (i in maxOf(0, arr.length() - 2) until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            obj.optString("user").takeIf { it.isNotBlank() }?.let { out += ChatMessage.user(it) }
            obj.optString("assistant").takeIf { it.isNotBlank() }?.let { out += ChatMessage.assistant(it) }
        }
        return out
    }

    private fun persistTurn(task: String, answer: String, messages: List<ChatMessage>) {
        val arr = store.readArray("turns.json")
        arr.put(JSONObject().apply {
            put("user", task)
            put("assistant", answer)
            put("at", System.currentTimeMillis())
            put("steps", messages.count { it.role == Role.TOOL })
        })
        store.writeArray("turns.json", JSONArray().apply {
            val start = maxOf(0, arr.length() - 20)
            for (i in start until arr.length()) put(arr.optJSONObject(i) ?: JSONObject())
        })
        store.writeObject("last_context.json", JSONObject().put("messages", messages.size))
    }

    private fun logLine(kind: String, text: String) = JSONObject().apply {
        put("at", System.currentTimeMillis())
        put("kind", kind)
        put("text", text)
    }.toString()

    fun shutdown() {
        scope.cancel()
    }
}
