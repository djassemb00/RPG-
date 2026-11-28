package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import org.json.JSONArray
import org.json.JSONObject

private const val NO_ADB =
    "ERROR: لا يوجد اتصال ADB. الإعدادات ← ADB ← اتصل (يتطلب تفعيل Wireless debugging واقتران الجهاز مرة واحدة)."

/**
 * Full-device control over ADB, in-process.
 *
 * Why this is materially stronger than the AccessibilityService: it does not need
 * the screen to be visible, it can drive any app (including ones that block
 * accessibility), and it can read system state directly. What it still cannot do is
 * bypass Android's pairing handshake or gain root.
 */
class AdbTools {

    class AdbConnectTool : AgentTool {
        override val spec = ToolSpec(
            name = "adb_connect",
            description = """
                افتح جلسة ADB مباشرة إلى منفذ Wireless debugging.
                يتطلب: تفعيل «التصحيح اللاسلكي» في خيارات المطوّر + منفذ الاتصال من شاشته.
                يستخدم مفتاح التطبيق الخاص؛ سيظهر طلب إذن على الجهاز في أول مرة.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("host", prop("string", "المضيف، افتراضي 127.0.0.1"))
                    .put("port", prop("integer", "منفذ ADB، افتراضي 5555")))
                .put("required", JSONArray()),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val cfg = ctx.adb.config()
            val host = args.optString("host").ifBlank { cfg.host }
            val port = args.optInt("port", cfg.port)
            return ctx.adb.connect(host, port)
        }
    }

    class AdbStatus : AgentTool {
        override val spec = ToolSpec(
            name = "adb_status",
            description = "حالة اتصال ADB: متصل؟ بأي هدف؟ بصمة المفتاح، وآخر خطأ.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val cfg = ctx.adb.config()
            return buildString {
                appendLine(if (ctx.adb.isConnected()) "متصل بـ ${ctx.adb.connectedTarget()}" else "غير متصل")
                appendLine("الهدف المحفوظ: ${cfg.host}:${cfg.port}")
                appendLine("بصمة المفتاح: ${ctx.adb.keyFingerprint()}")
                if (cfg.lastError.isNotBlank()) appendLine("آخر خطأ: ${cfg.lastError}")
                if (ctx.adb.isConnected()) {
                    ctx.adb.deviceInfo()?.trim()?.lines()?.forEachIndexed { i, line ->
                        val label = listOf("المصنّع", "الطراز", "أندرويد", "API").getOrNull(i)
                        appendLine("$label: ${line.trim()}")
                    }
                    ctx.adb.screenSize()?.let { (w, h) -> appendLine("الشاشة: ${w}x$h") }
                }
            }
        }
    }

    class AdbShell : AgentTool {
        override val spec = ToolSpec(
            name = "adb_shell",
            description = """
                نفّذ أمر shell عبر ADB بصلاحيات shell (أقوى من صلاحيات التطبيق، وليس root).
                يمكنه: pm list/uninstall، dumpsys، am start، settings، screencap، input، ملفات /sdcard.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("command", prop("string", "الأمر")))
                .put("required", JSONArray().put("command")),
            risk = Risk.HIGH,
        )

        private val blocked = listOf(
            "rm -rf /", "mkfs", "dd if=/dev/zero of=/dev/block", "reboot -p",
            "pm uninstall -k --user 0 com.android", "settings put secure",
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val command = args.optString("command").trim()
            if (command.isBlank()) return "ERROR: command مطلوب."
            blocked.firstOrNull { command.contains(it, ignoreCase = true) }?.let {
                return "DENIED: نمط خطِر محظور ('$it')."
            }
            val result = ctx.adb.shell(command) ?: return NO_ADB
            return "exit=${result.exitCode}\n${result.output.take(15_000)}" +
                if (result.error.isNotBlank()) "\n[stderr] ${result.error.take(2_000)}" else ""
        }
    }

    class AdbTap : AgentTool {
        override val spec = ToolSpec(
            name = "adb_tap",
            description = "نقرة عبر ADB بإحداثيات الشاشة — تعمل حتى مع تطبيقات تمنع إمكانية الوصول.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("x", prop("integer", "الإحداثي الأفقي"))
                    .put("y", prop("integer", "الإحداثي الرأسي")))
                .put("required", JSONArray().put("x").put("y")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val x = args.optInt("x", -1)
            val y = args.optInt("y", -1)
            if (x < 0 || y < 0) return "ERROR: إحداثيات غير صالحة."
            return if (ctx.adb.tap(x, y)) "تم النقر ($x, $y)." else "فشل النقر."
        }
    }

    class AdbSwipe : AgentTool {
        override val spec = ToolSpec(
            name = "adb_swipe",
            description = "سحب عبر ADB من نقطة إلى نقطة، بمدة بالمللي ثانية.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("x1", prop("integer", "بداية أفقية"))
                    .put("y1", prop("integer", "بداية رأسية"))
                    .put("x2", prop("integer", "نهاية أفقية"))
                    .put("y2", prop("integer", "نهاية رأسية"))
                    .put("duration", prop("integer", "المدة بالمللي ثانية")))
                .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val ok = ctx.adb.swipe(
                args.optInt("x1"), args.optInt("y1"),
                args.optInt("x2"), args.optInt("y2"),
                args.optInt("duration", 300),
            )
            return if (ok) "تم السحب." else "فشل السحب."
        }
    }

    class AdbText : AgentTool {
        override val spec = ToolSpec(
            name = "adb_text",
            description = "اكتب نصًا عبر ADB في الحقل النشط. العربية قد لا تُدعم في `input text` على بعض الأجهزة.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("text", prop("string", "النص")))
                .put("required", JSONArray().put("text")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val text = args.optString("text")
            if (text.isEmpty()) return "ERROR: text مطلوب."
            val ok = ctx.adb.inputText(text)
            return if (ok) "كُتب النص (${text.length} حرفًا)." else
                "فشل. ملاحظة: `input text` لا يدعم إلا ASCII على معظم الأجهزة."
        }
    }

    class AdbKey : AgentTool {
        override val spec = ToolSpec(
            name = "adb_key",
            description = "أرسل زرًا: back(4), home(3), recents(187), enter(66), power(26), volume_up(24), volume_down(25).",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", prop("string", "اسم الزر", listOf(
                        "back", "home", "recents", "enter", "power",
                        "volume_up", "volume_down", "delete", "tab",
                    ))))
                .put("required", JSONArray().put("name")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val code = when (args.optString("name")) {
                "back" -> 4
                "home" -> 3
                "recents" -> 187
                "enter" -> 66
                "power" -> 26
                "volume_up" -> 24
                "volume_down" -> 25
                "delete" -> 67
                "tab" -> 61
                else -> return "ERROR: زر غير معروف."
            }
            return if (ctx.adb.keyEvent(code)) "تم إرسال ${args.optString("name")}." else "فشل إرسال الزر."
        }
    }

    class AdbLaunch : AgentTool {
        override val spec = ToolSpec(
            name = "adb_launch",
            description = "شغّل تطبيقًا عبر ADB بمنحه إذنًا كاملًا (يعمل حتى لو مُنع من إمكانية الوصول).",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("package", prop("string", "اسم الحزمة")))
                .put("required", JSONArray().put("package")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val pkg = args.optString("package").trim()
            if (pkg.isBlank()) return "ERROR: package مطلوب."
            val result = ctx.adb.shell(
                "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
            ) ?: return NO_ADB
            return if (result.output.contains("No activities found")) {
                "ERROR: لا نشاط تشغيل لـ $pkg."
            } else {
                "تم تشغيل $pkg.\n${result.output.take(500)}"
            }
        }
    }

    class AdbCurrentApp : AgentTool {
        override val spec = ToolSpec(
            name = "adb_current_app",
            description = "ما التطبيق والنافذة في المقدمة الآن؟ (يعمل بلا خدمة إمكانية الوصول)",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            return ctx.adb.currentActivity()?.trim()?.ifBlank { "لا نتيجة." } ?: NO_ADB
        }
    }

    class AdbPackages : AgentTool {
        override val spec = ToolSpec(
            name = "adb_packages",
            description = "اعرض حزم التطبيقات. third_party=true للتطبيقات المثبّتة من المستخدم فقط.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("third_party", prop("boolean", "التطبيقات غير النظامية فقط"))),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val out = ctx.adb.listPackages(args.optBoolean("third_party", false)) ?: return NO_ADB
            val packages = out.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() }
            return "(${packages.size} حزمة)\n" + packages.take(120).joinToString("\n")
        }
    }

    class AdbScreenSize : AgentTool {
        override val spec = ToolSpec(
            name = "adb_screen_size",
            description = "أبعاد الشاشة بالبكسل — ضرورية لحساب إحداثيات النقر.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            if (!ctx.adb.isConnected()) return NO_ADB
            val size = ctx.adb.screenSize() ?: return "ERROR: تعذّر قراءة أبعاد الشاشة."
            return "الشاشة: ${size.first}x${size.second}. المنتصف ≈ (${size.first / 2}, ${size.second / 2})."
        }
    }

    /** Replaces the old executor-based stub: now a real ADB socket connection. */
    class AdbDisconnect : AgentTool {
        override val spec = ToolSpec(
            name = "adb_disconnect",
            description = "أغلق جلسة ADB الحالية.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            ctx.adb.disconnect()
            return "أُغلقت جلسة ADB."
        }
    }
}
