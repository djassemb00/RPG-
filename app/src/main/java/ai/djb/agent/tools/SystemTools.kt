package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import org.json.JSONObject

class SystemTools {

    class DeviceInfo : AgentTool {
        override val spec = ToolSpec(
            name = "device_info",
            description = "معلومات الجهاز: الطراز، إصدار أندرويد، البطارية، اللغة، حالة خدمات الوكيل.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val app = ctx.app
            val battery = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0) (level * 100 / scale) else -1
            return buildString {
                appendLine("الطراز: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("أندرويد: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                if (pct >= 0) appendLine("البطارية: $pct%")
                appendLine("اللغة: ${java.util.Locale.getDefault()}")
                appendLine("Accessibility: ${if (ai.djb.agent.services.AgentAccessibilityService.isEnabled()) "مفعّلة" else "معطّلة"}")
                append("Shell: ${ctx.shell.capability().name}")
            }
        }
    }

    class ListApps : AgentTool {
        override val spec = ToolSpec(
            name = "list_apps",
            description = "اعرض التطبيقات المثبّتة مع أسماء الحزم، لاختيار الحزمة الصحيحة قبل فتحها.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("filter", JSONObject().put("type", "string").put("description", "جزء من الاسم اختياري"))),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val filter = args.optString("filter").lowercase()
            val pm = ctx.app.packageManager
            val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val lines = apps.asSequence()
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { "${it.packageName}  —  ${pm.getApplicationLabel(it)}" }
                .filter { filter.isBlank() || it.lowercase().contains(filter) }
                .sorted()
                .toList()
            if (lines.isEmpty()) return "لا تطبيقات مطابقة."
            return "تطبيقات قابلة للفتح (${lines.size}):\n" + lines.take(80).joinToString("\n")
        }
    }

    class OpenUrl : AgentTool {
        override val spec = ToolSpec(
            name = "open_url",
            description = "افتح رابطًا في المتصفح.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("url", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("url")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val url = args.optString("url").trim()
            if (url.isBlank()) return "ERROR: url مطلوب."
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح $url"
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class OpenSettings : AgentTool {
        override val spec = ToolSpec(
            name = "open_settings",
            description = "افتح صفحة إعدادات نظام: wifi, bluetooth, battery, apps, accessibility, display, sound, location, storage.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("page", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("page")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val action = when (args.optString("page").lowercase()) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                else -> return "ERROR: صفحة غير معروفة '${args.optString("page")}'."
            }
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                ctx.app.startActivity(intent)
                "تم فتح الإعدادات: ${args.optString("page")}"
            }.getOrElse { "ERROR: ${it.message}" }
        }
    }

    class Clipboard : AgentTool {
        override val spec = ToolSpec(
            name = "clipboard",
            description = "اقرأ من الحافظة أو اكتب فيها. (قراءة الحافظة قد تحتاج أن يكون التطبيق في المقدمة)",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("action", prop("string", "read أو write", listOf("read", "write")))
                    .put("text", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("action")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val cm = ctx.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return when (args.optString("action")) {
                "read" -> cm.primaryClip?.getItemAt(0)?.coerceToText(ctx.app)?.toString()
                    ?.takeIf { it.isNotBlank() } ?: "الحافظة فارغة."
                "write" -> {
                    val text = args.optString("text")
                    if (text.isBlank()) return "ERROR: text مطلوب للكتابة."
                    cm.setPrimaryClip(ClipData.newPlainText("djb ai", text))
                    "تم النسخ إلى الحافظة (${text.length} حرفًا)."
                }
                else -> "ERROR: action يجب أن يكون read أو write."
            }
        }
    }

    class Vibrate : AgentTool {
        override val spec = ToolSpec(
            name = "vibrate",
            description = "اهتزاز قصير للجهاز (تنبيه للمستخدم).",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val vibrator = ctx.app.getSystemService(Vibrator::class.java) ?: return "ERROR: لا يوجد مُهتزّ."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
            return "تم الاهتزاز."
        }
    }

    class Toast : AgentTool {
        override val spec = ToolSpec(
            name = "toast",
            description = "أظهر رسالة قصيرة على الشاشة للمستخدم.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("text", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("text")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val text = args.optString("text")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx.app, text, android.widget.Toast.LENGTH_SHORT).show()
            }
            return "تم إظهار الرسالة."
        }
    }
}
