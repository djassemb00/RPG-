package ai.djb.agent

import ai.djb.agent.core.AdbBridge
import ai.djb.agent.core.BackupManager
import ai.djb.agent.core.AgentEngine
import ai.djb.agent.core.EventBus
import ai.djb.agent.core.GitHubBridge
import ai.djb.agent.core.GrowthEngine
import ai.djb.agent.core.HealthCheck
import ai.djb.agent.core.JsonStore
import ai.djb.agent.core.LessonLearner
import ai.djb.agent.core.TrainingData
import ai.djb.agent.core.LlmClient
import ai.djb.agent.core.PermissionBroker
import ai.djb.agent.core.TaskStore
import ai.djb.agent.core.ProviderRegistry
import ai.djb.agent.core.PatchSandbox
import ai.djb.agent.core.SelfCode
import ai.djb.agent.core.SelfUpgrade
import ai.djb.agent.core.SettingsStore
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.CloudBridge
import ai.djb.agent.core.ToolRegistry
import ai.djb.agent.core.WebBridge
import ai.djb.agent.core.ToolRegistryHolder
import ai.djb.agent.tools.ShellBridge
import android.app.Application
import java.io.File
import org.json.JSONObject

/**
 * Minimal service locator. The app is small enough that a manual object graph is
 * clearer (and easier to audit) than a DI framework.
 */
class AgentApp : Application() {

    lateinit var store: JsonStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var bus: EventBus
        private set
    lateinit var permissions: PermissionBroker
        private set
    lateinit var registry: ToolRegistry
        private set
    lateinit var providers: ProviderRegistry
        private set
    lateinit var client: LlmClient
        private set
    lateinit var engine: AgentEngine
        private set
    lateinit var shell: ShellBridge
        private set
    lateinit var tasks: TaskStore
        private set
    lateinit var adb: AdbBridge
        private set

    /** Everything the agent learned, moved in and out as one JSON file. */
    lateinit var backup: BackupManager
        private set

    /** Read-only view of own sources + staging area for proposed patches. */
    lateinit var selfCode: SelfCode
        private set

    /** Closed container: a candidate must pass every check here before promotion. */
    lateinit var sandbox: PatchSandbox
        private set

    /** A/B versions and the safety valve that restores the previous stable one. */
    lateinit var upgrade: SelfUpgrade
        private set

    /** Startup self-check that feeds the safety valve real faults, not guesses. */
    lateinit var health: HealthCheck
        private set

    /** The loop that turns its own track record into changes to itself. */
    lateinit var growth: GrowthEngine
        private set

    /** Distils successful runs into one-line lessons injected into future plans. */
    lateinit var lessons: LessonLearner
        private set

    /** Exports verified successful runs as fine-tuning JSONL. */
    lateinit var training: TrainingData
        private set

    /** Guarded outbound HTTP for reading the web. */
    lateinit var web: WebBridge
        private set

    /** Optional cloud worker: long tasks + cross-device memory. */
    lateinit var cloud: CloudBridge
        private set

    /** The build farm: the only route to a capability the APK doesn't have yet. */
    lateinit var github: GitHubBridge
        private set

    override fun onCreate() {
        super.onCreate()
        store = JsonStore(File(filesDir, "agent").apply { mkdirs() })
        tasks = TaskStore(store)
        adb = AdbBridge(store)
        settings = SettingsStore(store)
        bus = EventBus()
        permissions = PermissionBroker(bus)
        shell = ShellBridge(this)
        providers = ProviderRegistry(store).also { it.migrateLegacySettings(settings) }
        client = LlmClient(settings, providers, bus)
        backup = BackupManager(this, store, settings, providers, tasks, adb)
        selfCode = SelfCode(this, store)
        sandbox = PatchSandbox(store, settings)
        upgrade = SelfUpgrade(this, store, settings)
        health = HealthCheck(store, settings, providers)
        // Constructed before ToolContext because tools reach it: leaving it unset would
        // mean an uninitialised lateinit crash on the first launch after install.
        growth = GrowthEngine(store, settings, sandbox, client)
        lessons = LessonLearner(store, client)
        training = TrainingData(store)
        web = WebBridge()
        cloud = CloudBridge(settings)
        github = GitHubBridge(settings)

        val context = ToolContext(
            this, store, settings, permissions, bus, shell, adb, backup,
            selfCode, sandbox, upgrade, growth, lessons, training, web, cloud, github,
        )
        registry = ToolRegistry(context)
        registry.installDefaults()
        ToolRegistryHolder.attach(registry)

        // The safety valve fires on its own. A newly promoted version that fails the
        // startup check twice is reverted without the user having to notice anything.
        runCatching {
            val report = health.run()
            val state = upgrade.healthCheckOnStartup(!report.ok, report.summary())
            if (state.lastRollbackAt > 0 && state.lastRollbackReason.isNotBlank()) {
                store.appendLog(
                    JSONObject().apply {
                        put("at", System.currentTimeMillis())
                        put("kind", "upgrade")
                        put("text", "استعادة تلقائية: " + state.lastRollbackReason)
                    }.toString()
                )
            }
        }

        engine = AgentEngine(
            app = this,
            store = store,
            settings = settings,
            bus = bus,
            registry = registry,
            client = client,
            permissions = permissions,
            lessons = lessons,
        )

        // Re-arm anything scheduled; cheap and idempotent.
        runCatching { ai.djb.agent.services.TaskScheduler.scheduleAll(this) }
    }

    companion object {
        fun get(app: Application): AgentApp = app as AgentApp
    }
}
