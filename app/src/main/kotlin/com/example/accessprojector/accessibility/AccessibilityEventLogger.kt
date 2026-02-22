package com.example.accessprojector.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receives accessibility event broadcasts from [AppAccessibilityService]
 * and exposes them to UI components via a callback.
 *
 * Usage:
 * ```kotlin
 * val logger = AccessibilityEventLogger(context) { summary ->
 *     // Update your UI here
 * }
 * logger.register()
 * // ... later:
 * logger.unregister()
 * ```
 */
class AccessibilityEventLogger(
    private val context: Context,
    private val onEvent: (summary: String) -> Unit
) {
    companion object {
        private const val TAG = "A11yEventLogger"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val summary = intent?.getStringExtra(AppAccessibilityService.EXTRA_EVENT_SUMMARY)
                ?: return
            Log.v(TAG, "Received: $summary")
            onEvent(summary)
        }
    }

    fun register() {
        val filter = IntentFilter(AppAccessibilityService.ACTION_A11Y_EVENT)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Registered")
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
        Log.d(TAG, "Unregistered")
    }
}
