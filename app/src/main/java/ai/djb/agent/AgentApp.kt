package ai.djb.agent

import ai.djb.agent.core.AdbBridge
import ai.djb.agent.core.BackupManager
import ai.djb.agent.core.AgentEngine
import ai.djb.agent.core.EventBus
import ai.djb.agent.core.JsonStore
import ai.djb.agent.core.LlmClient
import ai.djb.agent.core.PermissionBroker
import ai.djb.agent.core.TaskStore
import ai.djb.agent.core.ProviderRegistry
import ai.djb.agent.core.SelfCode
import ai.djb.agent.core.SettingsStore
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolRegistry
import ai.djb.agent.core.ToolRegistryHolder
import ai.djb.agent.tools.ShellBridge
import android.app.Application
import java.io.File

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

        val context = ToolContext(this, store, settings, permissions, bus, shell, adb, backup, selfCode)
        registry = ToolRegistry(context)
        ToolRegistryHolder.attach(registry)
        registry.installDefaults()

        engine = AgentEngine(
            app = this,
            store = store,
            settings = settings,
            bus = bus,
            registry = registry,
            client = client,
            permissions = permissions,
        )

        // Re-arm anything scheduled; cheap and idempotent.
        runCatching { ai.djb.agent.services.TaskScheduler.scheduleAll(this) }
    }

    companion object {
        fun get(app: Application): AgentApp = app as AgentApp
    }
}
