package com.nullroute.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nullroute.MainActivity

class BlockerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val prefs = getSharedPreferences("nullroute_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("bypass_protection", false)) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // Monitor interaction within Settings app
            if (packageName == "com.android.settings") {
                val rootNode = rootInActiveWindow ?: return
                if (detectAttemptToDisable(rootNode)) {
                    // Redirect back to Home screen to prevent disabling or uninstalling
                    performGlobalAction(GLOBAL_ACTION_HOME)

                    // Launch Main Screen displaying warning message
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("BLOCKED_ATTEMPT", true)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun detectAttemptToDisable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        // Block UI if it contains "NullRoute" string label
        if (text.contains("NullRoute", ignoreCase = true) ||
            contentDesc.contains("NullRoute", ignoreCase = true)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (detectAttemptToDisable(child)) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        // Stub required by parent class
    }
}
