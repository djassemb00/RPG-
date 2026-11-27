package ai.djb.agent

import ai.djb.agent.core.AgentEngine
import ai.djb.agent.core.EventBus
import ai.djb.agent.core.JsonStore
import ai.djb.agent.core.LlmClient
import ai.djb.agent.core.PermissionBroker
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
    lateinit var client: LlmClient
        private set
    lateinit var engine: AgentEngine
        private set
    lateinit var shell: ShellBridge
        private set

    override fun onCreate() {
        super.onCreate()
        store = JsonStore(File(filesDir, "agent").apply { mkdirs() })
        settings = SettingsStore(store)
        bus = EventBus()
        permissions = PermissionBroker(bus)
        shell = ShellBridge(this)
        client = LlmClient(settings)

        val context = ToolContext(this, store, settings, permissions, bus, shell)
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
    }

    companion object {
        fun get(app: Application): AgentApp = app as AgentApp
    }
}
