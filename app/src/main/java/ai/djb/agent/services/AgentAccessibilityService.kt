package ai.djb.agent.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent's eyes and hands.
 *
 * Exposes a small, safe API: describe the screen, tap a labelled node, type into
 * the focused field, scroll, and press the system buttons. It deliberately has no
 * "automate silently in the background" mode — the service is only useful while a
 * window is on screen, which keeps the blast radius visible to the user.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** True when the user has enabled the service in system settings. */
    fun isReady(): Boolean = rootInActiveWindow != null

    fun packageName(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** Flattened, model-friendly view of what is currently on screen. */
    fun describeScreen(maxNodes: Int = 120): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("error", "لا يمكن قراءة الشاشة (الخدمة غير مفعّلة أو لا توجد نافذة).")

        val nodes = JSONArray()
        var count = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes || depth > 25) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable

            if (text.isNotEmpty() || desc.isNotEmpty() || (clickable && id.isNotEmpty())) {
                nodes.put(JSONObject().apply {
                    put("i", count)
                    if (text.isNotEmpty()) put("text", text.take(120))
                    if (desc.isNotEmpty()) put("desc", desc.take(120))
                    if (id.isNotEmpty()) put("id", id)
                    put("class", node.className?.toString()?.substringAfterLast('.') ?: "?")
                    if (clickable) put("clickable", true)
                    if (editable) put("editable", true)
                    if (node.isScrollable) put("scrollable", true)
                    if (!node.isEnabled) put("disabled", true)
                })
                count++
            }

            for (k in 0 until node.childCount) {
                walk(node.getChild(k), depth + 1)
            }
        }

        walk(root, 0)
        return JSONObject()
            .put("package", packageName())
            .put("nodes", nodes)
            .put("count", count)
    }

    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val byText = root.findAccessibilityNodeInfosByText(text).orEmpty()
        if (byText.isNotEmpty()) return byText
        val byId = runCatching { root.findAccessibilityNodeInfosByViewId(text) }.getOrNull().orEmpty()
        return byId
    }

    /** Taps the first node matching text; walks up to the nearest clickable ancestor. */
    fun clickByText(text: String): Boolean {
        val matches = findNodesByText(text)
        for (node in matches) {
            var target: AccessibilityNodeInfo? = node
            var guard = 0
            while (target != null && !target.isClickable && guard < 8) {
                target = target.parent
                guard++
            }
            val clickable = target ?: node
            if (runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                return true
            }
            // Fallback: synthetic tap in the middle of the node.
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }
        return false
    }

    fun clickNodeAt(index: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val collected = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isClickable) collected.add(node)
            for (k in 0 until node.childCount) walk(node.getChild(k))
        }
        walk(root)
        val node = collected.getOrNull(index) ?: return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return setTextOnFirstEditable(root, text)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    private fun setTextOnFirstEditable(root: AccessibilityNodeInfo, text: String): Boolean {
        val editable = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || editable.isNotEmpty()) return
            if (node.isEditable) editable.add(node)
            for (k in 0 until node.childCount) walk(node.getChild(k))
        }
        walk(root)
        val target = editable.firstOrNull() ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (k in 0 until node.childCount) {
                findScrollable(node.getChild(k))?.let { return it }
            }
            return null
        }
        val target = findScrollable(root) ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return target.performAction(action)
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun globalNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun globalQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /** Called before each new task so stale state can't leak between runs. */
    fun clearConversation() = Unit

    companion object {
        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun instance(): AgentAccessibilityService? = instance
        fun isEnabled(): Boolean = instance != null
    }
}
