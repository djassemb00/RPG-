package ai.djb.agent.ui

import ai.djb.agent.AgentApp
import ai.djb.agent.core.AgentEvent
import ai.djb.agent.core.Autonomy
import ai.djb.agent.core.LogLevel
import ai.djb.agent.core.ProviderSlot
import ai.djb.agent.core.Repeat
import ai.djb.agent.core.AgentPhase
import ai.djb.agent.core.ScheduledTask
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
    val role: AgentPhase = AgentPhase.ANY,
    val planModel: String = "",
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
    /** presetId -> live model list fetched from the provider. */
    val modelLists: Map<String, List<String>> = emptyMap(),
    val modelLoadingFor: String? = null,
    val modelListError: String? = null,
    val tasks: List<ScheduledTask> = emptyList(),
    val routingSummary: String = "",
    val adbConnected: Boolean = false,
    val adbTarget: String = "",
    val adbKeyFingerprint: String = "",
    val adbLastError: String = "",
    val adbBusy: Boolean = false,
    val adbResult: String? = null,
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
                    role = slot.role,
                    planModel = slot.planModel,
                )
            },
            tasks = app.tasks.load(),
            routingSummary = app.client.routingSummary(),
            adbConnected = app.adb.isConnected(),
            adbTarget = app.adb.connectedTarget().orEmpty(),
            adbKeyFingerprint = app.adb.keyFingerprint(),
            adbLastError = app.adb.config().lastError,
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

    /** "Why is it 404?" — shows the exact request shape that will be sent. */
    fun diagnose(presetId: String) {
        val slot = app.providers.load().firstOrNull { it.presetId == presetId } ?: return
        val fullUrl = "${slot.baseUrl.trimEnd('/')}/chat/completions"
        system(
            buildString {
                appendLine("تشخيص: ${slot.label}")
                appendLine("Base URL: ${slot.baseUrl}")
                appendLine("المسار الكامل: $fullUrl")
                appendLine("الموديل: ${slot.model}")
                appendLine(
                    "المفتاح: " + if (slot.key.isBlank()) "غير موجود!"
                    else "محفوظ (${slot.key.take(3)}… طوله ${slot.key.length})"
                )
                when {
                    !slot.baseUrl.startsWith("https://") && !slot.baseUrl.contains("192.168") ->
                        append("⚠ الـ Base URL لا يبدأ بـ https://")
                    fullUrl.contains("/v1/v1") ->
                        append("⚠ Base URL يحتوي /v1 مرتين — أزل النسخة الزائدة")
                    slot.model.isBlank() -> append("⚠ اسم الموديل فارغ")
                    else -> append("إن ظهر 404: تحقّق من المسار أعلاه، ثم استخدم «جلب الموديلات» لاختيار اسم صحيح.")
                }
            }
        )
    }

    /** Fetches the provider's live catalog so the user picks a name that exists now. */
    fun loadModels(presetId: String) {
        val slot = app.providers.load().firstOrNull { it.presetId == presetId } ?: return
        _state.value = _state.value.copy(modelLoadingFor = presetId, modelListError = null)
        viewModelScope.launch {
            app.client.listModels(slot).fold(
                onSuccess = { models ->
                    _state.value = _state.value.copy(
                        modelLoadingFor = null,
                        modelLists = _state.value.modelLists + (presetId to models),
                    )
                    system("جُلب ${models.size} موديلًا من ${slot.label}.")
                },
                onFailure = { err ->
                    val msg = err.message ?: "فشل جلب الموديلات"
                    _state.value = _state.value.copy(modelLoadingFor = null, modelListError = msg)
                    system("تعذّر جلب الموديلات: $msg")
                },
            )
        }
    }

    fun setProviderRole(presetId: String, role: AgentPhase) {
        app.providers.setRole(presetId, role)
        refreshSettings()
        system("دور المزوّد الآن: ${role.label()} — ${app.client.routingSummary()}")
    }

    fun setPlanModel(presetId: String, model: String) {
        app.providers.setPlanModel(presetId, model.trim())
        refreshSettings()
        system("موديل التخطيط: ${model.ifBlank { "(يستخدم الموديل الأساسي)" }}")
    }

    fun setModel(presetId: String, model: String) {
        val slot = app.providers.load().firstOrNull { it.presetId == presetId } ?: return
        app.providers.upsert(slot.copy(model = model))
        refreshSettings()
        system("موديل ${slot.label} الآن: $model")
    }

    /**
     * Auto-repair a retired model id: read the provider catalog, then try candidates
     * until one actually answers.
     */
    fun autoFixModel(presetId: String) {
        val slot = app.providers.load().firstOrNull { it.presetId == presetId } ?: return
        _state.value = _state.value.copy(modelLoadingFor = presetId)
        viewModelScope.launch {
            val catalog = app.client.listModels(slot).getOrNull().orEmpty()
            val preset = ai.djb.agent.core.ProviderPreset.byId(presetId)
            val candidates = buildList {
                preset?.let { add(it.defaultModel) }
                catalog.filterNot { id ->
                    val lower = id.lowercase()
                    listOf("whisper", "embed", "tts", "guard", "rerank", "moderation")
                        .any { lower.contains(it) }
                }.sortedBy { chatScore(it) }.forEach { add(it) }
            }.distinct()

            val working = app.client.resolveWorkingModel(slot, candidates.take(8))
            _state.value = _state.value.copy(modelLoadingFor = null)
            if (working != null) {
                app.providers.upsert(slot.copy(model = working))
                refreshSettings()
                system("تم إصلاح الموديل: $working (كان ${slot.model})")
            } else {
                system(
                    "لم أجد موديلًا يستجيب — راجع «تشخيص» للمسار، وتأكد أن المفتاح من نفس المزوّد، " +
                        "ثم اجلب الموديلات واختر واحدًا يدويًا."
                )
            }
        }
    }

    private fun chatScore(model: String): Int {
        val m = model.lowercase()
        var s = 0
        if (m.contains("instruct") || m.contains("chat")) s -= 2
        if (m.contains("free") || m.contains("flash")) s -= 1
        if (m.contains("mini") || m.contains("small")) s += 2
        if (m.contains("audio") || m.contains("vision")) s += 1
        s += m.length / 25
        return s
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

    // ---- scheduled tasks ----

    fun addTask(title: String, prompt: String, hour: Int, minute: Int, repeat: Repeat) {
        val task = ScheduledTask(
            id = java.util.UUID.randomUUID().toString().take(8),
            title = title,
            prompt = prompt,
            hour = hour,
            minute = minute,
            repeat = repeat,
        ).let { it.copy(nextRunAt = it.computeNextRun()) }
        app.tasks.upsert(task)
        ai.djb.agent.services.TaskScheduler.scheduleOne(app, task)
        refreshSettings()
        system("جُدولت «$title». التشغيل القادم بعد ${((task.nextRunAt - System.currentTimeMillis()) / 60000)} دقيقة.")
    }

    fun toggleTask(id: String, enabled: Boolean) {
        val updated = app.tasks.toggle(id, enabled)
        updated.firstOrNull { it.id == id }?.let {
            if (enabled) ai.djb.agent.services.TaskScheduler.scheduleOne(app, it)
            else ai.djb.agent.services.TaskScheduler.cancel(app, id)
        }
        refreshSettings()
    }

    fun deleteTask(id: String) {
        app.tasks.remove(id)
        ai.djb.agent.services.TaskScheduler.cancel(app, id)
        refreshSettings()
        system("حُذفت المهمة $id.")
    }

    fun runTaskNow(id: String) {
        val task = app.tasks.load().firstOrNull { it.id == id } ?: return
        add(FeedItem(0, FeedKind.USER, "تشغيل يدوي", task.prompt, now()))
        app.engine.submit(task.prompt)
    }

    // ---- ADB ----

    fun adbConnect(host: String, port: Int) {
        _state.value = _state.value.copy(adbBusy = true, adbResult = null)
        viewModelScope.launch {
            val result = app.adb.connect(host, port)
            _state.value = _state.value.copy(adbBusy = false, adbResult = result)
            refreshSettings()
            system(result)
        }
    }

    fun adbDisconnect() {
        app.adb.disconnect()
        refreshSettings()
        system("أُغلقت جلسة ADB.")
    }

    fun adbProbe() {
        _state.value = _state.value.copy(adbBusy = true)
        viewModelScope.launch {
            val info = app.adb.deviceInfo()
            val size = app.adb.screenSize()
            val activity = app.adb.currentActivity()
            val text = buildString {
                appendLine(info?.trim() ?: "لا معلومات")
                size?.let { appendLine("الشاشة: ${it.first}x${it.second}") }
                appendLine(activity?.trim() ?: "")
            }
            _state.value = _state.value.copy(adbBusy = false, adbResult = text.trim())
            system(text.trim())
        }
    }

    fun adbSavePort(host: String, port: Int) {
        val cfg = app.adb.config()
        app.adb.saveConfig(cfg.copy(host = host, port = port))
        system("حُفظ هدف ADB: $host:$port")
    }

    fun adbOpenWirelessSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onFailure { system("افتح الإعدادات ← خيارات المطوّر ← التصحيح اللاسلكي يدويًا.") }
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
