package com.example.accessprojector.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AppAccessibilityService monitors and interacts with UI events across all apps.
 *
 * Capabilities:
 *  - Captures window/view accessibility events
 *  - Traverses the view hierarchy to find node info
 *  - Performs gestures (tap, swipe) programmatically
 *  - Broadcasts events to the rest of the app via LocalBroadcastManager
 */
class AppAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "A11yService"

        /** Broadcast action emitted when an accessibility event is captured. */
        const val ACTION_A11Y_EVENT = "com.example.accessprojector.A11Y_EVENT"

        /** Extra key carrying a human-readable event summary string. */
        const val EXTRA_EVENT_SUMMARY = "event_summary"

        /** Singleton reference so other components can check service state. */
        @Volatile
        var instance: AppAccessibilityService? = null
            private set
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
        broadcastEvent("Service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "Accessibility service destroyed")
    }

    // ── Core callback ──────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val summary = buildEventSummary(event)
        Log.d(TAG, summary)

        serviceScope.launch {
            processEvent(event, summary)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // ── Event processing ───────────────────────────────────────────────────────

    private fun processEvent(event: AccessibilityEvent, summary: String) {
        broadcastEvent(summary)

        // Example: react to window state changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            Log.d(TAG, "Active package: $packageName")
        }
    }

    private fun buildEventSummary(event: AccessibilityEvent): String {
        val typeName = AccessibilityEvent.eventTypeToString(event.eventType)
        val pkg = event.packageName ?: "unknown"
        val cls = event.className ?: "unknown"
        val text = event.text.joinToString(", ").ifEmpty { "<no text>" }
        return "[$typeName] pkg=$pkg cls=$cls text=$text"
    }

    private fun broadcastEvent(summary: String) {
        val intent = Intent(ACTION_A11Y_EVENT).apply {
            putExtra(EXTRA_EVENT_SUMMARY, summary)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── View hierarchy helpers ─────────────────────────────────────────────────

    /**
     * Recursively collects all text from the active window's node tree.
     */
    fun collectAllText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        return collectText(root).also { root.recycle() }
    }

    private fun collectText(node: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        if (!node.text.isNullOrBlank()) {
            results += node.text.toString()
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            results += collectText(child)
            child.recycle()
        }
        return results
    }

    /**
     * Find the first node with the given resource-id (e.g. "com.foo.app:id/button").
     */
    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()
    }

    // ── Gesture helpers ────────────────────────────────────────────────────────

    /**
     * Perform a tap gesture at screen coordinates [x], [y].
     */
    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Tap at ($x,$y) completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Tap at ($x,$y) cancelled")
            }
        }, null)
    }

    /**
     * Perform a swipe gesture from ([startX],[startY]) to ([endX],[endY])
     * over [durationMs] milliseconds.
     */
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300L
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Swipe completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Swipe cancelled")
            }
        }, null)
    }

    /**
     * Press the global BACK button.
     */
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * Press the global HOME button.
     */
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Open the recents / overview screen.
     */
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
