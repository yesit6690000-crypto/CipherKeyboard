package com.cipher.keyboard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Does exactly one thing: when asked, looks at the current screen for a clickable "Send" button
 * and taps it. Used only right after AES-encrypting a message, so "type, hit Enter" can actually
 * deliver the message in one motion instead of needing a manual second tap on the host app's own
 * send button (which a keyboard has no way to reach on its own).
 *
 * This does NOT log, store, or transmit anything it sees on screen -- it inspects the current
 * window only at the moment attemptTapSend() is called, purely to locate a button to click, and
 * discards everything immediately after.
 */
class AutoSendAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: AutoSendAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        /** Returns true if a send-like button was found and tapped, false if nothing matched. */
        fun attemptTapSend(): Boolean {
            return try {
                instance?.tapSendButton() ?: false
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally does nothing with the event stream -- we only ever look at the screen
        // on-demand inside tapSendButton(), never reactively track what's happening.
    }

    override fun onInterrupt() {}

    private fun tapSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findSendNode(root)
        if (target != null) {
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.recycle()
            return clicked
        }
        return false
    }

    /** Depth-first search for a clickable node that looks like a send button by its label. */
    private fun findSendNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null // safety guard against pathological view trees
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val looksLikeSend = (desc.contains("send") || text.contains("send")) &&
            !desc.contains("sender") && !text.contains("sender")
        if (looksLikeSend && node.isClickable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }
}
