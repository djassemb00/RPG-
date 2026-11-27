package ai.djb.agent.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * The single chokepoint that decides whether an action may run.
 *
 * Rules are intentionally boring and visible: risk level + user autonomy +
 * a per-tool allow-list the user can clear at any time.
 */
class PermissionBroker(private val bus: EventBus) {

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val ids = AtomicLong(0)

    suspend fun approve(tool: String, args: String, risk: Risk, autonomy: Autonomy): Boolean {
        if (risk == Risk.LOW) return true
        if (autonomy == Autonomy.FULL) return true
        if (autonomy == Autonomy.BALANCED && risk == Risk.MEDIUM) return true

        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        bus.emit(AgentEvent.ConfirmNeeded(id, tool, args, risk))
        val decision = withTimeoutOrNull(120_000) { deferred.await() } ?: false
        pending.remove(id)
        return decision
    }

    fun resolve(id: Long, approved: Boolean) {
        pending.remove(id)?.complete(approved)
    }

    fun cancelAll() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }
}

/** Helper for serialising tool arguments into human-readable audit lines. */
fun JSONArray.toPlainList(): List<String> = (0 until length()).map { optString(it) }
