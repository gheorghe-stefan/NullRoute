package com.nullroute.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nullroute.MainActivity

class BlockerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val isDebuggable = (applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val packageName = event.packageName?.toString() ?: return

            if (packageName == "com.android.settings" || packageName.contains("packageinstaller", ignoreCase = true)) {
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

    private fun detectAttemptToDisable(rootNode: AccessibilityNodeInfo): Boolean {
        val allTexts = mutableListOf<String>()
        collectAllTexts(rootNode, allTexts)

        val hasNullRoute = allTexts.any { it.contains("NullRoute", ignoreCase = true) }
        if (!hasNullRoute) return false

        // 1. Check for App Info / Uninstall / Force Stop attempt
        val isAppInfoUninstallAttempt = allTexts.any { text ->
            text.contains("Uninstall", ignoreCase = true) ||
            text.contains("Force stop", ignoreCase = true) ||
            text.contains("Disable", ignoreCase = true) ||
            text.contains("Clear data", ignoreCase = true) ||
            text.contains("App info", ignoreCase = true) ||
            text.contains("Storage & cache", ignoreCase = true)
        }

        // 2. Check for Accessibility Service toggle off attempt
        val isAccessibilityToggleAttempt = allTexts.any { text ->
            text.contains("Accessibility", ignoreCase = true) ||
            text.contains("Use NullRoute", ignoreCase = true) ||
            text.contains("Turn off", ignoreCase = true) ||
            text.contains("Stop NullRoute", ignoreCase = true)
        }

        // 3. Check for VPN Disconnect / Forget VPN attempt
        val isVpnDisconnectAttempt = allTexts.any { text ->
            text.contains("Disconnect", ignoreCase = true) ||
            text.contains("Forget VPN", ignoreCase = true) ||
            text.contains("Forget network", ignoreCase = true) ||
            text.contains("Forget", ignoreCase = true)
        }

        // If NullRoute is present AND one of the disabling action indicators is matched
        return isAppInfoUninstallAttempt || isAccessibilityToggleAttempt || isVpnDisconnectAttempt
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            list.add(text)
        }
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank()) {
            list.add(contentDesc)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectAllTexts(child, list)
        }
    }

    override fun onInterrupt() {
        // Stub required by parent class
    }
}
