package ai.djb.agent.ui

import ai.djb.agent.AgentApp
import ai.djb.agent.core.AgentEvent
import ai.djb.agent.core.Autonomy
import ai.djb.agent.core.LogLevel
import ai.djb.agent.core.ProviderSlot
import ai.djb.agent.core.Risk
import ai.djb.agent.services.AgentAccessibilityService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FeedKind { USER, AGENT, TOOL, ANSWER, SYSTEM, ERROR }

data class FeedItem(
    val id: Long,
    val kind: FeedKind,
    val title: String,
    val body: String = "",
    val at: String = "",
)

data class ProviderSlotView(
    val presetId: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val configured: Boolean,
    val enabled: Boolean,
    val hasKey: Boolean,
    val successCount: Int,
    val failCount: Int,
    val lastError: String?,
)

data class PendingConfirmation(val id: Long, val tool: String, val args: String, val risk: Risk)

data class UiState(
    val feed: List<FeedItem> = emptyList(),
    val running: Boolean = false,
    val pending: PendingConfirmation? = null,
    val accessibilityOn: Boolean = false,
    val configured: Boolean = false,
    val apiBaseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    val agentName: String = "djb",
    val autonomy: Autonomy = Autonomy.ASK,
    val selfImprove: Boolean = true,
    val maxSteps: Int = 12,
    val extraInstruction: String = "",
    val skillsCount: Int = 0,
    val memoryCount: Int = 0,
    val rulesCount: Int = 0,
    val providers: List<ProviderSlotView> = emptyList(),
    val providerChain: String = "",
    val providerTesting: String? = null,
    val providerTestResult: String? = null,
)

class AgentViewModel(private val app: AgentApp) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var seq = 0L
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        viewModelScope.launch {
            app.bus.events.collect { event -> handle(event) }
        }
        refreshSettings()
        system("جاهز. اكتب مهمتك، أو اضغط ⚙ للتكوين.")
    }

    fun refreshSettings() {
        val s = app.settings
        _state.value = _state.value.copy(
            configured = s.isConfigured,
            apiBaseUrl = s.apiBaseUrl,
            model = s.model,
            hasApiKey = s.apiKey.isNotBlank(),
            agentName = s.agentName,
            autonomy = s.autonomy,
            selfImprove = s.selfImprove,
            maxSteps = s.maxSteps,
            extraInstruction = s.extraInstruction,
            accessibilityOn = AgentAccessibilityService.isEnabled(),
            skillsCount = app.store.readSkills().size,
            memoryCount = app.store.readMemory().size,
            rulesCount = app.store.readRules().size,
            providers = app.providers.load().map { slot ->
                ProviderSlotView(
                    presetId = slot.presetId,
                    label = slot.label,
                    baseUrl = slot.baseUrl,
                    model = slot.model,
                    configured = slot.configured,
                    enabled = slot.enabled,
                    hasKey = slot.key.isNotBlank(),
                    successCount = slot.successCount,
                    failCount = slot.failCount,
                    lastError = slot.lastError,
                )
            },
            providerChain = app.client.describeChain(),
            configured = app.client.hasUsableProvider(),
        )
    }

    fun onResume() = refreshSettings()

    private fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.Answer -> add(FeedItem(0, FeedKind.ANSWER, "النتيجة", event.text, now()))
            is AgentEvent.Log -> add(
                FeedItem(
                    0,
                    if (event.level == LogLevel.ERROR) FeedKind.ERROR else FeedKind.AGENT,
                    when (event.level) {
                        LogLevel.INFO -> "المساعد"
                        LogLevel.WARN -> "تنبيه"
                        LogLevel.ERROR -> "خطأ"
                    },
                    event.text,
                    now(),
                )
            )
            is AgentEvent.ToolStarted -> add(
                FeedItem(0, FeedKind.TOOL, "⚙ ${event.name}", event.args.take(600), now())
            )
            is AgentEvent.ToolFinished -> add(
                FeedItem(0, FeedKind.TOOL, "↳ ${event.name}", event.result.take(1200), now())
            )
            is AgentEvent.ConfirmNeeded -> _state.value = _state.value.copy(
                pending = PendingConfirmation(event.id, event.tool, event.args, event.risk)
            )
            is AgentEvent.RunningChanged -> {
                _state.value = _state.value.copy(running = event.running)
                if (!event.running) {
                    _state.value = _state.value.copy(pending = null)
                    refreshSettings()
                }
            }
        }
    }

    private fun add(item: FeedItem) {
        seq++
        val withId = item.copy(id = seq)
        _state.value = _state.value.copy(feed = (_state.value.feed + withId).takeLast(300))
    }

    private fun system(text: String) = add(FeedItem(0, FeedKind.SYSTEM, text, "", now()))

    private fun now() = clock.format(Date())

    fun submit(task: String) {
        if (task.isBlank()) return
        if (_state.value.running) {
            system("المهمة السابقة لا تزال قيد التنفيذ. أوقفها أولًا.")
            return
        }
        add(FeedItem(0, FeedKind.USER, "أنت", task, now()))
        app.engine.submit(task)
    }

    fun stop() = app.engine.cancel()

    fun decide(approved: Boolean) {
        val pending = _state.value.pending ?: return
        app.permissions.resolve(pending.id, approved)
        add(
            FeedItem(
                0,
                FeedKind.SYSTEM,
                if (approved) "سمحت بـ ${pending.tool}" else "رفضت ${pending.tool}",
                "",
                now(),
            )
        )
        _state.value = _state.value.copy(pending = null)
    }

    fun clearFeed() {
        _state.value = _state.value.copy(feed = emptyList())
        system("تم تفريغ السجل.")
    }

    // ---- settings actions ----

    // ---- provider chain ----

    fun addOrUpdateProvider(presetId: String, label: String, baseUrl: String, model: String, key: String) {
        val existing = app.providers.load().firstOrNull { it.presetId == presetId }
        app.providers.upsert(
            ProviderSlot(
                presetId = presetId,
                label = label,
                baseUrl = baseUrl.trim(),
                model = model.trim(),
                key = key.trim().ifBlank { existing?.key.orEmpty() },
                enabled = true,
                priority = existing?.priority ?: app.providers.load().size,
            )
        )
        refreshSettings()
        system("تم حفظ مزوّد $label. سلسلة الطلب: ${app.client.describeChain()}")
    }

    fun removeProvider(presetId: String, label: String) {
        app.providers.remove(presetId)
        refreshSettings()
        system("حُذف مزوّد $label.")
    }

    fun setProviderEnabled(presetId: String, enabled: Boolean) {
        app.providers.setEnabled(presetId, enabled)
        refreshSettings()
    }

    fun moveProvider(presetId: String, up: Boolean) {
        app.providers.move(presetId, up)
        refreshSettings()
    }

    fun testProvider(presetId: String) {
        val slot = app.providers.load().firstOrNull { it.presetId == presetId } ?: return
        _state.value = _state.value.copy(providerTesting = presetId, providerTestResult = null)
        viewModelScope.launch {
            val result = app.client.testProvider(slot)
            _state.value = _state.value.copy(providerTesting = null, providerTestResult = result)
            system(result)
        }
    }

    fun saveBehaviour(name: String, autonomy: Autonomy, selfImprove: Boolean, maxSteps: Int, extra: String) {
        app.settings.agentName = name
        app.settings.autonomy = autonomy
        app.settings.selfImprove = selfImprove
        app.settings.maxSteps = maxSteps
        app.settings.extraInstruction = extra
        userPrefsSaved()
    }

    private fun userPrefsSaved() {
        refreshSettings()
        system("تم حفظ الإعدادات.")
    }

    fun deleteSkill(name: String) {
        val remaining = app.store.readSkills().filterNot { it.name == name }
        app.store.writeSkills(remaining)
        refreshSettings()
        system("حُذفت المهارة $name.")
    }

    fun clearMemory() {
        app.store.writeMemory(emptyMap())
        refreshSettings()
        system("تم تفريغ الذاكرة.")
    }

    fun clearRules() {
        app.store.writeRules(emptyList())
        refreshSettings()
        system("تم حذف كل القواعد.")
    }

    fun skills(): List<ai.djb.agent.core.Skill> = app.store.readSkills()
    fun memory(): Map<String, String> = app.store.readMemory()
    fun rules(): List<String> = app.store.readRules()
    fun logs(): List<String> = app.store.readLog(300)

    fun clearLog() = app.store.clearLog()

    override fun onCleared() {
        super.onCleared()
        app.bus.emit(AgentEvent.RunningChanged(false))
    }
}
