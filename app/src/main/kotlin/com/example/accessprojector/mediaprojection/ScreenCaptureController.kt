package com.example.accessprojector.mediaprojection

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Helper that wraps the MediaProjection permission flow and delegates to
 * [ScreenCaptureService] for the actual screen-capture work.
 *
 * Usage in an [AppCompatActivity]:
 * ```kotlin
 * val controller = ScreenCaptureController(this) { path ->
 *     // handle screenshot path
 * }
 * controller.registerLauncher()   // call before onStart
 * controller.requestCapture()     // triggers system permission dialog
 * controller.requestScreenshot()  // captures a frame (service must be running)
 * controller.stopCapture()        // releases projection
 * ```
 */
class ScreenCaptureController(
    private val activity: AppCompatActivity,
    private val onScreenshotTaken: (path: String) -> Unit
) {
    companion object {
        private const val TAG = "ScreenCaptureCtrl"
    }

    private var launcher: ActivityResultLauncher<Intent>? = null

    private val screenshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val path = intent?.getStringExtra(ScreenCaptureService.EXTRA_SCREENSHOT_PATH) ?: return
            Log.d(TAG, "Screenshot received: $path")
            onScreenshotTaken(path)
        }
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    /**
     * Must be called during [AppCompatActivity.onCreate] (before the activity starts).
     */
    fun registerLauncher() {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.i(TAG, "MediaProjection permission granted")
                startCaptureService(result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "MediaProjection permission denied (resultCode=${result.resultCode})")
            }
        }
    }

    fun registerBroadcastReceiver() {
        val filter = IntentFilter(ScreenCaptureService.ACTION_SCREENSHOT_TAKEN)
        ContextCompat.registerReceiver(
            activity,
            screenshotReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterBroadcastReceiver() {
        runCatching { activity.unregisterReceiver(screenshotReceiver) }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Shows the system MediaProjection consent dialog. */
    fun requestCapture() {
        val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        launcher?.launch(mgr.createScreenCaptureIntent())
            ?: Log.e(TAG, "Launcher not registered – call registerLauncher() in onCreate")
    }

    /** Sends a screenshot command to the running [ScreenCaptureService]. */
    fun requestScreenshot() {
        val intent = Intent(activity, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_TAKE_SCREENSHOT
        }
        activity.startService(intent)
    }

    /** Stops the running [ScreenCaptureService]. */
    fun stopCapture() {
        val intent = Intent(activity, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        activity.startService(intent)
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = ScreenCaptureService.buildIntent(activity, resultCode, data)
        ContextCompat.startForegroundService(activity, intent)
    }
}
