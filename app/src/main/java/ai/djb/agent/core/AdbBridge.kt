package ai.djb.agent.core

import dadb.AdbKeyPair
import dadb.Dadb
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
    private fun keyPair(): AdbKeyPair {
        val private = File(store.privateDir(), "adb_key")
        val public = File(store.privateDir(), "adb_key.pub")
        if (!private.exists() || !public.exists()) {
            AdbKeyPair.generate(private, public)
            // Lock the private half down where the platform allows it.
            runCatching {
                private.setReadable(false, false)
                private.setReadable(true, true)
            }
        }
        return AdbKeyPair.read(private, public)
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
            ShellResult(response.exitCode, response.output, response.errorOutput)
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

    // ---- screen understanding without the accessibility service ----

    /**
     * Dumps the live view hierarchy through `uiautomator` and flattens it into a
     * compact, model-friendly list.
     *
     * This is the missing piece for blind ADB control: taps are coordinates, and
     * without knowing where things are the agent is guessing. uiautomator gives us
     * text + bounds for every visible node, with no accessibility service involved.
     */
    suspend fun describeScreenViaAdb(maxNodes: Int = 100): List<ScreenNode> {
        val path = "/sdcard/djb_ui_${System.currentTimeMillis()}.xml"
        val dumped = shell("uiautomator dump $path 2>/dev/null; cat $path")
            ?: return emptyList()
        val xml = dumped.output
        shell("rm -f $path")

        val start = xml.indexOf("<hierarchy")
        if (start < 0) return emptyList()
        val document = xml.substring(start)

        val nodes = ArrayList<ScreenNode>()
        runCatching {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(document))
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && nodes.size < maxNodes) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node") {
                    val text = parser.getAttributeValue(null, "text").orEmpty()
                    val desc = parser.getAttributeValue(null, "content-desc").orEmpty()
                    val cls = parser.getAttributeValue(null, "class").orEmpty().substringAfterLast('.')
                    val bounds = parser.getAttributeValue(null, "bounds").orEmpty()
                    val clickable = parser.getAttributeValue(null, "clickable") == "true"
                    val boundsRe = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
                    val m = boundsRe.find(bounds)
                    if ((text.isNotBlank() || desc.isNotBlank()) && m != null) {
                        val (x1, y1, x2, y2) = m.destructured
                        nodes.add(
                            ScreenNode(
                                index = nodes.size,
                                text = text,
                                description = desc,
                                className = cls,
                                clickable = clickable,
                                centerX = (x1.toInt() + x2.toInt()) / 2,
                                centerY = (y1.toInt() + y2.toInt()) / 2,
                            )
                        )
                    }
                }
                event = parser.next()
            }
        }
        return nodes
    }

    /** Finds a node by visible text / content description and taps its centre. */
    suspend fun tapText(query: String): Boolean {
        val node = describeScreenViaAdb().firstOrNull {
            it.text.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        } ?: return false
        return tap(node.centerX, node.centerY)
    }

    data class ScreenNode(
        val index: Int,
        val text: String,
        val description: String,
        val className: String,
        val clickable: Boolean,
        val centerX: Int,
        val centerY: Int,
    ) {
        fun describe(): String = buildString {
            append("[$index] ")
            append(className.ifBlank { "?" })
            if (text.isNotBlank()) append(" «$text»")
            if (description.isNotBlank()) append(" {" + description + "}")
            if (clickable) append(" [قابل للنقر]")
            append(" @(").append(centerX).append(",").append(centerY).append(")")
        }
    }

    /** Saves a PNG screenshot inside app storage; returns the file path. */
    suspend fun screenshot(dir: java.io.File): String? {
        val remote = "/data/local/tmp/djb_shot.png"
        if (shell("screencap -p $remote")?.exitCode != 0) return null
        val base64 = shell("base64 $remote")?.output ?: return null
        shell("rm -f $remote")
        return runCatching {
            val bytes = android.util.Base64.decode(base64.replace("\n", ""), android.util.Base64.DEFAULT)
            dir.mkdirs()
            val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
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
