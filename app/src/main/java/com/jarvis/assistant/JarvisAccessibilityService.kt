package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets Jarvis see the screen and operate other apps (tap, type, scroll, back/home...).
 * The user must switch this on once in Settings > Accessibility.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- reading ----------

    fun readScreen(): String {
        val root = rootInActiveWindow
            ?: return "Screen not readable right now (loading, or a secure screen). Try again in a moment."
        val sb = StringBuilder("app=${root.packageName}\n")
        walk(root, sb)
        return if (sb.length > 7000) sb.substring(0, 7000) + "\n...(truncated; scroll for more)" else sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || sb.length > 7500 || !n.isVisibleToUser) return
        val label = (n.text ?: n.contentDescription)?.toString()?.replace("\n", " ")
        if (!label.isNullOrBlank() || n.isClickable || n.isEditable) {
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append(
                when {
                    n.isEditable -> "[input] "
                    n.isClickable -> "[button] "
                    else -> ""
                }
            )
            sb.append(label?.take(120) ?: "(no label)")
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), sb)
    }

    // ---------- acting ----------

    fun clickText(text: String): String {
        val root = rootInActiveWindow ?: return "No window to tap in."
        val nodes = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        val node = nodes.firstOrNull() ?: return "No on-screen element matches '$text'."
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "Tapped '$text'."
            }
            cur = cur.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun typeText(text: String, submit: Boolean): String {
        val root = rootInActiveWindow ?: return "No window to type in."
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return "No text field found. Tap a text field first."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "The text field refused the text."
        if (submit && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return if (submit) "Typed and pressed enter." else "Typed."
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null || !n.isVisibleToUser) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            findEditable(n.getChild(i))?.let { return it }
        }
        return null
    }

    fun scroll(forward: Boolean): String {
        val root = rootInActiveWindow ?: return "No window."
        val node = findScrollable(root) ?: return "Nothing scrollable on this screen."
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) "Scrolled." else "Cannot scroll further."
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null || !n.isVisibleToUser) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            findScrollable(n.getChild(i))?.let { return it }
        }
        return null
    }

    fun tap(x: Float, y: Float): String {
        val p = Path().apply { moveTo(x, y) }
        return gesture(p, 60)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): String {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return gesture(p, durationMs)
    }

    private fun gesture(path: Path, durationMs: Long): String {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return if (dispatchGesture(g, null, null)) "Gesture done." else "Gesture failed."
    }

    fun press(button: String): String {
        val action: Int? = when (button) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_menu" -> GLOBAL_ACTION_POWER_DIALOG
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else null
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else null
            else -> null
        }
        if (action == null) return "Unknown or unsupported button '$button'."
        return if (performGlobalAction(action)) "Pressed $button." else "Could not press $button."
    }
}
