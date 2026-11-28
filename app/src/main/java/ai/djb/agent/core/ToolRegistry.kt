package ai.djb.agent.core

import ai.djb.agent.skills.SkillRuntime
import ai.djb.agent.tools.AccessibilityTools
import ai.djb.agent.tools.AdbTools
import ai.djb.agent.tools.BackupTools
import ai.djb.agent.tools.AppControlTools
import ai.djb.agent.tools.MemoryTools
import ai.djb.agent.tools.PatternTools
import ai.djb.agent.tools.ScheduleTools
import ai.djb.agent.tools.SelfCodeTools
import ai.djb.agent.tools.ShellTools
import ai.djb.agent.tools.SkillAuthoringTools
import ai.djb.agent.tools.SystemTools
import ai.djb.agent.tools.VisionTools
import org.json.JSONObject

/** Holds the live registry so the AccessibilityService can reach it without a binder. */
object ToolRegistryHolder {
    @Volatile
    private var registry: ToolRegistry? = null

    fun attach(reg: ToolRegistry) {
        registry = reg
    }

    fun tools(): ToolRegistry? = registry
}

class ToolRegistry(private val ctx: ToolContext) {

    private val tools = LinkedHashMap<String, AgentTool>()

    /**
     * Registers the whole tool surface. Order matters only for the prompt listing,
     * and every entry here is a capability the agent genuinely has — no placeholders.
     */
    fun installDefaults() {
        // --- memory / self-authored rules ---
        register(MemoryTools.Remember())
        register(MemoryTools.Recall())
        register(MemoryTools.AddRule())
        register(MemoryTools.ListRules())

        // --- device facts & simple intents ---
        register(SystemTools.DeviceInfo())
        register(SystemTools.ListApps())
        register(SystemTools.OpenUrl())
        register(SystemTools.OpenSettings())
        register(SystemTools.Clipboard())
        register(SystemTools.Vibrate())
        register(SystemTools.Toast())

        // --- app control intents ---
        register(AppControlTools.LaunchApp())
        register(AppControlTools.SendSms())
        register(AppControlTools.ComposeEmail())
        register(AppControlTools.Dial())
        register(AppControlTools.SetAlarm())
        register(AppControlTools.SetTimer())
        register(AppControlTools.MediaControl())
        register(AppControlTools.SetVolume())
        register(AppControlTools.ToggleSetting())
        register(AppControlTools.CapturePhoto())
        register(AppControlTools.PickContact())

        // --- screen automation (needs accessibility) ---
        register(AccessibilityTools.ReadScreen())
        register(AccessibilityTools.ClickText())
        register(AccessibilityTools.ClickIndex())
        register(AccessibilityTools.Tap())
        register(AccessibilityTools.Swipe())
        register(AccessibilityTools.WriteText())
        register(AccessibilityTools.Scroll())
        register(AccessibilityTools.SystemButton())
        register(AccessibilityTools.Wait())

        // --- shell (app UID, honestly scoped) ---
        register(ShellTools.RunShell())
        register(ShellTools.ShellCapability())

        // --- ADB over TCP, in-process: full device control once paired ---
        register(AdbTools.AdbConnectTool())
        register(AdbTools.AdbStatus())
        register(AdbTools.AdbShell())
        register(AdbTools.AdbTap())
        register(AdbTools.AdbSwipe())
        register(AdbTools.AdbText())
        register(AdbTools.AdbKey())
        register(AdbTools.AdbLaunch())
        register(AdbTools.AdbCurrentApp())
        register(AdbTools.AdbPackages())
        register(AdbTools.AdbScreenSize())
        register(AdbTools.AdbDescribeScreen())
        register(AdbTools.AdbClickText())
        register(AdbTools.AdbScreenshot())
        register(AdbTools.AdbDisconnect())

        // --- moving what it learned between installs ---
        register(BackupTools.ExportBackup())
        register(BackupTools.InspectBackup())

        // --- noticing its own repetition ---
        register(PatternTools.SuggestPatterns())
        register(PatternTools.MineSkill())

        // --- reading and patching its own source ---
        register(SelfCodeTools.ListSource())
        register(SelfCodeTools.ReadSource())
        register(SelfCodeTools.SearchSource())
        register(SelfCodeTools.ProposePatch())
        register(SelfCodeTools.ListPatches())

        // --- vision: see the screen, not just its text ---
        register(VisionTools.Look())
        register(VisionTools.ScreenText())

        // --- scheduling its own future work ---
        val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        register(ScheduleTools.ScheduleTask(stamp))
        register(ScheduleTools.ListScheduledTask(stamp))
        register(ScheduleTools.CancelScheduledTask())

        // --- self-modification: authoring & running its own skills ---
        register(SkillAuthoringTools.CreateSkill())
        register(SkillAuthoringTools.SaveSkill())
        register(SkillAuthoringTools.RunSkill())
        register(SkillAuthoringTools.ListSkills())
        register(SkillAuthoringTools.DeleteSkill())
        register(SkillAuthoringTools.InspectSkill())
    }

    fun register(tool: AgentTool) {
        tools[tool.spec.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun all(): List<AgentTool> = tools.values.toList()

    fun specs(): List<ToolSpec> = tools.values.map { it.spec }

    fun spec(name: String): ToolSpec? = tools[name]?.spec

    fun has(name: String): Boolean = tools.containsKey(name)

    /** Executes a tool by name. Never throws: failures become readable text for the model. */
    suspend fun invoke(
        name: String,
        args: JSONObject,
        autonomy: Autonomy,
        onApproval: suspend (String, Risk) -> Boolean,
    ): String {
        val tool = tools[name]
            ?: return "ERROR: لا توجد أداة باسم '$name'. الأدوات المتاحة: ${tools.keys.joinToString()}"

        if (!onApproval(name, tool.spec.risk)) {
            return "DENIED: رفض المستخدم تنفيذ '$name'. لا تكرّر المحاولة، واقترح بديلاً أو اسأل المستخدم."
        }

        return try {
            tool.execute(args, ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun skillRuntime(): SkillRuntime = SkillRuntime(ctx)
}
