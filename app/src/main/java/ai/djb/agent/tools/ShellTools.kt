package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import org.json.JSONObject

/**
 * Shell access, honestly scoped: this runs as the app's own UID. It can read
 * system properties, list files the app can reach, and drive a user-configured
 * ADB-over-Wi-Fi setup. It cannot silently escape the sandbox, and it says so.
 */
class ShellTools {

    class RunShell : AgentTool {
        override val spec = ToolSpec(
            name = "run_shell",
            description = "نفّذ أمر shell داخل صلاحيات التطبيق. مفيد لقراءة خصائص النظام وملفات التطبيق. ليس صلاحية root.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("command", JSONObject().put("type", "string").put("description", "الأمر"))
                    .put("timeout_ms", JSONObject().put("type", "integer")))
                .put("required", org.json.JSONArray().put("command")),
            risk = Risk.HIGH,
        )

        private val blocked = listOf(
            "rm -rf /", "mkfs", "dd if=/dev/zero of=/dev/block", "reboot", "shutdown",
            ":(){", "chmod -R 777 /", "pm uninstall --user 0 com.android",
            "settings put secure enabled_accessibility_services",
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val command = args.optString("command").trim()
            if (command.isBlank()) return "ERROR: command مطلوب."
            val forbidden = blocked.firstOrNull { command.contains(it, ignoreCase = true) }
            if (forbidden != null) {
                return "DENIED: الأمر يحتوي على نمط خطِر محظور ('$forbidden'). لن أنفّذه."
            }
            val timeout = args.optLong("timeout_ms", 20_000).coerceIn(1_000, 120_000)
            val result = ctx.shell.execute(command, timeout)
            return "exit=${result.exitCode}\n${result.asText()}"
        }
    }

    class ShellCapability : AgentTool {
        override val spec = ToolSpec(
            name = "shell_capability",
            description = "ما مستوى صلاحيات shell المتاح فعلًا على هذا الجهاز؟",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val cap = ctx.shell.capability()
            val probe = ctx.shell.execute("id; getprop ro.product.model 2>/dev/null", 3_000)
            return "القدرة: ${cap.name}\nملاحظة: التنفيذ يتم بصلاحيات التطبيق نفسه، لا root.\n---\n${probe.asText()}"
        }
    }

    class AdbConnect : AgentTool {
        override val spec = ToolSpec(
            name = "adb_connect",
            description = "حاول الاتصال بـ ADB اللاسلكي عبر localhost:5555. يتطلب أن يفعّل المستخدم Wireless debugging وربط المنفذ. لا يعمل من دون ذلك.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("port", JSONObject().put("type", "integer").put("description", "المنفذ، افتراضي 5555"))),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val port = args.optInt("port", 5555)
            if (ctx.shell.capability() != ShellBridge.Capability.ADB_AVAILABLE) {
                return "UNAVAILABLE: لا يوجد ملف adb تنفيذي على الجهاز. " +
                    "التفعيل يتطلب نسخة adb بناءً معماريًا أو اتصالًا من حاسوب (adb pair / adb connect). " +
                    "لن أزيّف نجاحًا هنا."
            }
            val result = ctx.shell.execute("adb connect 127.0.0.1:$port && adb devices", 15_000)
            return "exit=${result.exitCode}\n${result.asText()}"
        }
    }
}
