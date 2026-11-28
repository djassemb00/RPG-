package ai.djb.agent.tools

import ai.djb.agent.core.AgentTool
import ai.djb.agent.core.Risk
import ai.djb.agent.core.ToolContext
import ai.djb.agent.core.ToolSpec
import ai.djb.agent.core.prop
import ai.djb.agent.services.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vision: lets the agent look at the screen instead of only reading text nodes.
 *
 * Two capture paths, chosen for what's available:
 *  1. Accessibility `takeScreenshot` (Android 11+) — no pairing, fast.
 *  2. ADB `screencap` — works when the service is off but ADB is paired.
 *
 * The image is handed back to the model as a multimodal user message, so the next
 * turn reasons over pixels (icon-only buttons, canvas UIs, images) that the
 * uiautomator dump cannot describe.
 */
class VisionTools {

    class Look : AgentTool {
        override val spec = ToolSpec(
            name = "look",
            description = """
                انظر إلى الشاشة كصورة والتقط ما تراه. استخدمها عندما تكون الأزرار أيقونات بلا نص،
                أو عندما تفشل قراءة الشجرة النصية، أو لفحص تخطيط غير واضح.
                يمكنك تحديد سؤال تركّز عليه.
            """.trimIndent(),
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("question", prop("string", "ما الذي تريد معرفته من الصورة؟ اختياري")))
                .put("required", JSONArray()),
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            val question = args.optString("question").ifBlank { "صف ما تراه على الشاشة وحدّد العناصر القابلة للنقر." }

            // Path 1: accessibility screenshot (no pairing needed).
            val service = AgentAccessibilityService.instance()
            val viaAccessibility = service?.takeScreenshotBlocking()
            if (viaAccessibility != null) {
                PendingVision.attach(viaAccessibility, question)
                return "التقطت صورة الشاشة عبر خدمة إمكانية الوصول (${viaAccessibility.size / 1024} كيلوبايت). " +
                    "سأراها في دوري القادم. السؤال: $question"
            }

            // Path 2: ADB screencap.
            if (ctx.adb.isConnected()) {
                val dir = java.io.File(ctx.store.privateDir(), "screens")
                val path = ctx.adb.screenshot(dir)
                if (path != null) {
                    val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
                    if (bytes != null) {
                        PendingVision.attach(bytes, question)
                        return "التقطت صورة الشاشة عبر ADB (${bytes.size / 1024} كيلوبايت) وحفظتها في: $path. " +
                            "سأراها في دوري القادم."
                    }
                }
                return "ERROR: تعذّر التقاط الصورة عبر ADB."
            }

            return "ERROR: لا يمكن التقاط الصورة. فعّل خدمة إمكانية الوصول (أندرويد 11+) أو اربط ADB."
        }
    }

    class ScreenText : AgentTool {
        override val spec = ToolSpec(
            name = "screen_text_only",
            description = "اقرأ نص الشاشة فقط (بلا صورة) — أسرع وأرخص من look.",
            risk = Risk.LOW,
        )

        override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
            AgentAccessibilityService.instance()?.let { service ->
                return service.describeScreen().toString()
            }
            if (ctx.adb.isConnected()) {
                val nodes = ctx.adb.describeScreenViaAdb()
                if (nodes.isNotEmpty()) return nodes.joinToString("\n") { it.describe() }
            }
            return "ERROR: لا خدمة إمكانية الوصول ولا اتصال ADB."
        }
    }
}

/**
 * One-slot mailbox between a tool call and the next model turn.
 *
 * A tool cannot emit a message itself; the engine drains this right after the tool
 * result and appends the image as a user message. That keeps the loop in one place
 * and means the model always sees the picture on its very next step.
 */
object PendingVision {
    @Volatile
    private var bytes: ByteArray? = null

    @Volatile
    private var question: String = ""

    @Synchronized
    fun attach(image: ByteArray, askedQuestion: String) {
        bytes = image
        question = askedQuestion
    }

    @Synchronized
    fun drain(): Pair<ByteArray, String>? {
        val image = bytes ?: return null
        val q = question
        bytes = null
        question = ""
        return image to q
    }
}
