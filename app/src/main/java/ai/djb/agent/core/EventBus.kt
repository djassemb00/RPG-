package ai.djb.agent.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class LogLevel { INFO, WARN, ERROR }

sealed interface AgentEvent {
    data class Log(val text: String, val level: LogLevel = LogLevel.INFO) : AgentEvent
    data class ToolStarted(val name: String, val args: String) : AgentEvent
    data class ToolFinished(val name: String, val result: String) : AgentEvent
    data class ConfirmNeeded(val id: Long, val tool: String, val args: String, val risk: Risk) : AgentEvent
    data class RunningChanged(val running: Boolean) : AgentEvent
    data class Answer(val text: String) : AgentEvent
}

/** One-way bus from the agent loop to the UI. */
class EventBus {
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    fun emit(event: AgentEvent) {
        _events.tryEmit(event)
    }
}
