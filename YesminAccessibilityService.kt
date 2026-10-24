package com.yesmin.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Phone ke haath: screen padhna, tap, scroll, swipe, type, global buttons. */
class YesminAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: YesminAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this; Bus.log("Accessibility connected"); Bus.resume.value++ }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private val w get() = resources.displayMetrics.widthPixels
    private val h get() = resources.displayMetrics.heightPixels

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun readScreen(max: Int = 150): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("error", "screen read nahi ho paayi")
        val arr = JSONArray()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || arr.length() >= max) return
            if (n.isVisibleToUser) {
                val t = if (n.isPassword) "" else (n.text?.toString() ?: "")
                val d = n.contentDescription?.toString() ?: ""
                if (t.isNotBlank() || d.isNotBlank() || n.isClickable || n.isEditable) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    val o = JSONObject()
                    if (t.isNotBlank()) o.put("text", t.take(120))
                    if (d.isNotBlank() && d != t) o.put("desc", d.take(80))
                    n.viewIdResourceName?.let { o.put("id", it.substringAfterLast('/')) }
                    o.put("cls", n.className?.toString()?.substringAfterLast('.') ?: "")
                    if (n.isClickable) o.put("click", true)
                    if (n.isScrollable) o.put("scroll", true)
                    if (n.isEditable) o.put("edit", true)
                    if (n.isPassword) o.put("password", true)
                    if (n.isChecked) o.put("checked", true)
                    o.put("x", r.centerX() * 100 / w).put("y", r.centerY() * 100 / h)
                    arr.put(o)
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return JSONObject().put("package", root.packageName?.toString() ?: "")
            .put("elements", arr).put("note", "x,y screen ke % mein hain (tap_xy ke liye)")
    }

    private suspend fun gesture(path: Path, dur: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceAtLeast(1))).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    suspend fun tapPx(x: Float, y: Float, dur: Long = 60): Boolean =
        gesture(Path().apply { moveTo(x, y) }, dur)

    suspend fun tapPct(xp: Double, yp: Double, dur: Long = 60) =
        tapPx((xp / 100.0 * w).toFloat(), (yp / 100.0 * h).toFloat(), dur)

    suspend fun tapText(text: String, nth: Int = 0, long: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        val node = nodes.getOrNull(nth) ?: return false
        if (!long) {
            var c: AccessibilityNodeInfo? = node
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val r = Rect(); node.getBoundsInScreen(r)
        return tapPx(r.centerX().toFloat(), r.centerY().toFloat(), if (long) 700 else 60)
    }

    /** Ungli ki disha mein swipe (up = ungli upar). */
    suspend fun swipeFinger(dir: String): Boolean {
        val cx = w / 2f; val cy = h / 2f
        val dx = w * 0.35f; val dy = h * 0.3f
        val (sx, sy, ex, ey) = when (dir) {
            "up" -> listOf(cx, cy + dy, cx, cy - dy)
            "down" -> listOf(cx, cy - dy, cx, cy + dy)
            "left" -> listOf(cx + dx, cy, cx - dx, cy)
            else -> listOf(cx - dx, cy, cx + dx, cy)
        }
        return gesture(Path().apply { moveTo(sx, sy); lineTo(ex, ey) }, 350)
    }

    /** Content kis taraf jaaye: down = neeche ka hissa dikhe. */
    suspend fun scroll(dir: String): Boolean {
        val root = rootInActiveWindow
        val sc = root?.let { findScrollable(it) }
        if (sc != null) {
            val act = if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (sc.performAction(act)) return true
        }
        val finger = when (dir) { "down" -> "up"; "up" -> "down"; "right" -> "left"; else -> "right" }
        return swipeFinger(finger)
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; findScrollable(c)?.let { return it } }
        return null
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; findEditable(c)?.let { return it } }
        return null
    }

    private fun inputNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
    }

    fun isPasswordFocused(): Boolean = inputNode()?.isPassword == true

    fun typeText(text: String, replace: Boolean): Boolean {
        val f = inputNode() ?: return false
        if (f.isPassword) return false
        val hint = if (Build.VERSION.SDK_INT >= 26) f.hintText?.toString() else null
        var cur = f.text?.toString() ?: ""
        if (replace || (hint != null && cur == hint)) cur = ""
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun pressEnter(): Boolean {
        val f = inputNode() ?: return false
        return if (Build.VERSION.SDK_INT >= 30)
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
    }

    fun global(key: String): Boolean = when (key) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "split_screen" -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        else -> false
    }
}
