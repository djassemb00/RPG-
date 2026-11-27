package ai.djb.agent.tools

import ai.djb.agent.core.ShellBridgeRef
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs shell commands under the app's own UID (no root). This is enough for
 * reading system state and for ADB-over-Wi-Fi flows the user sets up manually,
 * and it is deliberately not advertised as "full device control".
 */
class ShellBridge(private val app: android.app.Application) : ShellBridgeRef {

    enum class Capability {
        BASIC_SHELL,      // can run `sh`, but only inside the app sandbox
        ADB_AVAILABLE,    // an `adb` binary exists on the device
        UNAVAILABLE,      // shell couldn't be started at all
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
        fun asText(): String = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("[stderr] ").append(stderr.trim())
            }
            if (isEmpty()) append("(no output, exit=$exitCode)")
        }
    }

    override fun capability(): Capability {
        val adb = runCatching {
            ProcessBuilder("sh", "-c", "command -v adb >/dev/null 2>&1 && echo yes").start()
                .apply { waitFor(2, TimeUnit.SECONDS) }
                .inputStream.bufferedReader().readText().trim()
        }.getOrNull()
        if (adb == "yes") return Capability.ADB_AVAILABLE
        val sh = runCatching {
            ProcessBuilder("sh", "-c", "echo ok").start()
                .apply { waitFor(2, TimeUnit.SECONDS) }
                .inputStream.bufferedReader().readText().trim()
        }.getOrNull()
        return if (sh == "ok") Capability.BASIC_SHELL else Capability.UNAVAILABLE
    }

    override suspend fun execute(command: String, timeoutMs: Long): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("sh", "-c", command)
                    .directory(app.filesDir)
                    .redirectErrorStream(false)
                    .start()

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@withContext Result(-1, "", "انتهت المهلة (${timeoutMs}ms) — تم إيقاف الأمر.")
                }

                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                Result(process.exitValue(), out.take(20_000), err.take(8_000))
            }.getOrElse { e ->
                Result(-1, "", "تعذّر تشغيل shell: ${e.message}")
            }
        }
}
