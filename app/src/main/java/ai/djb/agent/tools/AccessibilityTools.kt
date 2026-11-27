package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import ai.djb.agent.services.AgentAccessibilityService
import org.json.JSONObject

private const val NOT_ENABLED =
    "ERROR: خدمة Accessibility غير مفعّلة. اطلب من المستخدم تفعيلها من الإعدادات ← إمكانية الوصول ← djb ai."

class AccessibilityTools {

    class ReadScreen : AgentTool {
        override val spec = ToolSpec(
            name = "read_screen",
            description = "اقرأ ما على الشاشة الآن: التطبيق الحالي وقائمة العناصر (نص، وصف، قابل للنقر، قابل للكتابة).",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            return service.describeScreen().toString()
        }
    }

    class ClickText : AgentTool {
        override val spec = ToolSpec(
            name = "click_text",
            description = "انقر على عنصر يحمل هذا النص أو الوصف على الشاشة الحالية.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("text", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("text")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            val text = args.optString("text").trim()
            if (text.isBlank()) return "ERROR: text مطلوب."
            return if (service.clickByText(text)) "تم النقر على '$text'." else "لم أجد عنصرًا قابلًا للنقر بالنص '$text'."
        }
    }

    class ClickIndex : AgentTool {
        override val spec = ToolSpec(
            name = "click_index",
            description = "انقر على العنصر رقم (i) من نتيجة read_screen. ثابت الرقم = العنصر القابل للنقر بالترتيب.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("i", JSONObject().put("type", "integer")))
                .put("required", org.json.JSONArray().put("i")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            val i = args.optInt("i", -1)
            if (i < 0) return "ERROR: i مطلوب."
            return if (service.clickNodeAt(i)) "تم النقر على العنصر $i." else "لا عنصر قابل للنقر بالرقم $i."
        }
    }

    class Tap : AgentTool {
        override val spec = ToolSpec(
            name = "tap",
            description = "نقرة بإحداثيات الشاشة (بالبكسل). استخدمها فقط عند فشل click_text.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("x", JSONObject().put("type", "number"))
                    .put("y", JSONObject().put("type", "number")))
                .put("required", org.json.JSONArray().put("x").put("y")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            val x = args.optDouble("x", -1.0).toFloat()
            val y = args.optDouble("y", -1.0).toFloat()
            if (x < 0 || y < 0) return "ERROR: إحداثيات غير صالحة."
            return if (service.tap(x, y)) "تم النقر عند ($x, $y)." else "فشل النقر."
        }
    }

    class Swipe : AgentTool {
        override val spec = ToolSpec(
            name = "swipe",
            description = "اسحب من نقطة إلى نقطة (بالبكسل).",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("x1", JSONObject().put("type", "number"))
                    .put("y1", JSONObject().put("type", "number"))
                    .put("x2", JSONObject().put("type", "number"))
                    .put("y2", JSONObject().put("type", "number"))
                    .put("duration", JSONObject().put("type", "integer")))
                .put("required", org.json.JSONArray().put("x1").put("y1").put("x2").put("y2")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            val ok = service.swipe(
                args.optDouble("x1").toFloat(),
                args.optDouble("y1").toFloat(),
                args.optDouble("x2").toFloat(),
                args.optDouble("y2").toFloat(),
                args.optLong("duration", 300),
            )
            return if (ok) "تم السحب." else "فشل السحب."
        }
    }

    class WriteText : AgentTool {
        override val spec = ToolSpec(
            name = "write_text",
            description = "اكتب نصًا في الحقل النشط/القابل للكتابة على الشاشة.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("text", JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("text")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            val text = args.optString("text")
            if (text.isEmpty()) return "ERROR: text مطلوب."
            return if (service.typeText(text)) "تمت الكتابة." else "لم أجد حقلًا قابلًا للكتابة."
        }
    }

    class Scroll : AgentTool {
        override val spec = ToolSpec(
            name = "scroll",
            description = "مرّر الشاشة للأمام أو للخلف.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("direction", prop("string", "اتجاه التمرير", listOf("down", "up"))))
                .put("required", org.json.JSONArray().put("direction")),
            risk = Risk.HIGH,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            val forward = args.optString("direction", "down") == "down"
            return if (service.scroll(forward)) "تم التمرير." else "لا عنصر قابل للتمرير."
        }
    }

    class SystemButton : AgentTool {
        override val spec = ToolSpec(
            name = "system_button",
            description = "اضغط زر نظام: back, home, recents, notifications, quick_settings.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("button", prop("string", "زر النظام", listOf("back", "home", "recents", "notifications", "quick_settings"))))
                .put("required", org.json.JSONArray().put("button")),
            risk = Risk.MEDIUM,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val service = AgentAccessibilityService.instance() ?: return NOT_ENABLED
            val ok = when (args.optString("button")) {
                "back" -> service.globalBack()
                "home" -> service.globalHome()
                "recents" -> service.globalRecents()
                "notifications" -> service.globalNotifications()
                "quick_settings" -> service.globalQuickSettings()
                else -> false
            }
            return if (ok) "تم الضغط على ${args.optString("button")}." else "فشل تنفيذ الزر."
        }
    }

    class Wait : AgentTool {
        override val spec = ToolSpec(
            name = "wait",
            description = "انتظر عددًا من المللي ثانية (حتى 10 ثوانٍ) لتحميل شاشة أو إشعار.",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("ms", JSONObject().put("type", "integer")))
                .put("required", org.json.JSONArray().put("ms")),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val ms = args.optLong("ms", 500).coerceIn(50, 10_000)
            kotlinx.coroutines.delay(ms)
            return "انتظرت $ms مللي ثانية."
        }
    }
}
