package ai.djb.agent.core

import dev.mobile.dadb.Dadb
import dev.mobile.dadb.DadbKeyPair
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ADB over TCP, implemented in-process — no `adb` binary, no adb server.
 *
 * This matters because the Android SDK's adb is not available to a normal app.
 * `dadb` speaks the ADB wire protocol directly, so the app can talk to a device's
 * adbd (this phone's own wireless-debugging port, or another device on the LAN)
 * once the user pairs it and authorises our key.
 *
 * Honest boundaries, enforced by the OS rather than by us:
 *  - The user must enable Wireless debugging and pair (pairing code) at least once.
 *    Android will not let an app enable it silently.
 *  - Our key must be authorised; an unauthorised session yields AUTH errors, which
 *    we report verbatim instead of faking success.
 *  - Root is not granted; `run-as-adb` gives shell access, which is already far
 *    beyond what the AccessibilityService can do (no screen visible = no taps).
 */
class AdbBridge(private val store: JsonStore) {

    private val file = "adb.json"

    /** Persisted connection config, including the keypair path. */
    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = 5555,
        val autoConnect: Boolean = false,
        val lastOkAt: Long = 0,
        val lastError: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("host", host)
            put("port", port)
            put("auto_connect", autoConnect)
            put("last_ok_at", lastOkAt)
            put("last_error", lastError)
        }

        companion object {
            fun fromJson(obj: JSONObject) = Config(
                host = obj.optString("host", "127.0.0.1"),
                port = obj.optInt("port", 5555),
                autoConnect = obj.optBoolean("auto_connect", false),
                lastOkAt = obj.optLong("last_ok_at"),
                lastError = obj.optString("last_error"),
            )
        }
    }

    data class ShellResult(val exitCode: Int, val output: String, val error: String)

    @Volatile
    private var connection: Dadb? = null

    @Volatile
    private var connectedTo: String? = null

    fun config(): Config = Config.fromJson(store.readObject(file))

    fun saveConfig(config: Config) = store.writeObject(file, config.toJson())

    fun isConnected(): Boolean = connection != null

    fun connectedTarget(): String? = connectedTo

    /**
     * The app's ADB private key. Generated once, kept in app-private storage, and
     * shown to the user as a fingerprint so they can recognise it in the system
     * "Allow USB debugging?" dialog.
     */
    private fun keyPair(): DadbKeyPair {
        val private = File(store.privateDir(), "adb_key")
        val public = File(store.privateDir(), "adb_key.pub")
        if (!private.exists() || !public.exists()) {
            val generated = DadbKeyPair.generate()
            private.writeBytes(generated.privateKeyPem.toByteArray())
            public.writeBytes(generated.publicKeyPem.toByteArray())
            // Lock down the key file where the platform allows it.
            runCatching { private.setReadable(false, false); private.setReadable(true, true) }
        }
        return DadbKeyPair.read(private, public)
    }

    fun keyFingerprint(): String = runCatching {
        val public = File(store.privateDir(), "adb_key.pub")
        if (!public.exists()) "لا مفتاح بعد — سيُولَّد عند أول اتصال"
        else {
            val bytes = public.readText().trim().toByteArray()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString(":") { "%02X".format(it) }.take(59)
        }
    }.getOrDefault("غير متاح")

    /** Opens the ADB session. Throws nothing: the error is returned as text. */
    suspend fun connect(host: String, port: Int): String = withContext(Dispatchers.IO) {
        disconnect()
        val target = "$host:$port"
        try {
            val dadb = Dadb.create(host, port, keyPair())
            // A cheap command proves the session is authorised, not just open.
            val probe = dadb.shell("echo djb-ok")
            if (!probe.output.contains("djb-ok")) {
                runCatching { dadb.close() }
                val msg = "الاتصال فُتح لكن الأمر لم ينفّذ. غالبًا المنفذ ليس adbd أو الجهاز لم يأذن للمفتاح."
                saveConfig(config().copy(lastError = msg))
                return@withContext "ERROR: $msg"
            }
            connection = dadb
            connectedTo = target
            saveConfig(config().copy(host = host, port = port, lastOkAt = System.currentTimeMillis(), lastError = ""))
            "متصل بـ $target (ADB مباشر، بلا root). بصمة مفتاحنا: ${keyFingerprint()}"
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            saveConfig(config().copy(lastError = msg.take(300)))
            "ERROR: تعذّر الاتصال بـ $target — $msg"
        }
    }

    fun disconnect() {
        runCatching { connection?.close() }
        connection = null
        connectedTo = null
    }

    /** Runs a shell command over ADB. Returns null when there is no session. */
    suspend fun shell(command: String): ShellResult? = withContext(Dispatchers.IO) {
        val dadb = connection ?: return@withContext null
        try {
            val response = dadb.shell(command)
            ShellResult(response.exitCode, response.output, response.error)
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- high-level device actions built on top of the shell ----

    suspend fun tap(x: Int, y: Int): Boolean =
        shell("input tap $x $y")?.exitCode == 0

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean =
        shell("input swipe $x1 $y1 $x2 $y2 $durationMs")?.exitCode == 0

    /** Text is quoted for the shell; spaces and Arabic are handled by single quotes. */
    suspend fun inputText(text: String): Boolean {
        val escaped = text.replace("'", "'\\''")
        return shell("input text '$escaped'")?.exitCode == 0
    }

    suspend fun keyEvent(code: Int): Boolean = shell("input keyevent $code")?.exitCode == 0

    suspend fun currentActivity(): String? =
        shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2")?.output

    suspend fun screenSize(): Pair<Int, Int>? {
        val out = shell("wm size")?.output ?: return null
        val match = Regex("(\\d+)x(\\d+)").find(out) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    suspend fun screenshotTo(path: String): Boolean {
        // screencap writes the PNG on the device; we pull it afterwards.
        val ok = shell("screencap -p $path")?.exitCode == 0
        return ok
    }

    suspend fun listPackages(onlyThirdParty: Boolean = false): String? =
        shell(if (onlyThirdParty) "pm list packages -3" else "pm list packages")?.output

    suspend fun deviceInfo(): String? = shell(
        "getprop ro.product.manufacturer; getprop ro.product.model; " +
            "getprop ro.build.version.release; getprop ro.build.version.sdk"
    )?.output

    companion object {
        /** Ports Android uses for wireless debugging: 5555 is the classic, others are random. */
        fun quickPorts(): List<Int> = listOf(5555, 5556, 5557)

        fun randomSecureSuffix(): Int = SecureRandom().nextInt(1000) + 5555
    }
}
