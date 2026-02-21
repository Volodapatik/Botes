package com.example.accessprojector.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accessprojector.R
import com.example.accessprojector.accessibility.AccessibilityEventLogger
import com.example.accessprojector.accessibility.AppAccessibilityService
import com.example.accessprojector.databinding.ActivityMainBinding
import com.example.accessprojector.mediaprojection.ScreenCaptureController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main entry point of the app.
 *
 * Provides controls for:
 *  - Enabling the Accessibility Service
 *  - Starting / stopping MediaProjection screen capture
 *  - Taking screenshots on demand
 *  - Viewing live accessibility event logs
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var captureController: ScreenCaptureController
    private lateinit var eventLogger: AccessibilityEventLogger

    private val logBuffer = StringBuilder()

    // Permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        log("Notification permission ${if (granted) "granted" else "denied"}")
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureController = ScreenCaptureController(this) { path ->
            onScreenshotSaved(path)
        }
        captureController.registerLauncher()

        eventLogger = AccessibilityEventLogger(this) { summary ->
            runOnUiThread { log("[A11y] $summary") }
        }

        setupClickListeners()
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        captureController.registerBroadcastReceiver()
        eventLogger.register()
        refreshStatus()
    }

    override fun onStop() {
        captureController.unregisterBroadcastReceiver()
        eventLogger.unregister()
        super.onStop()
    }

    // ── UI Setup ───────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnAccessibility.setOnClickListener {
            if (isAccessibilityEnabled()) {
                showDisableAccessibilityDialog()
            } else {
                openAccessibilitySettings()
            }
        }

        binding.btnStartCapture.setOnClickListener {
            log("Requesting MediaProjection permission…")
            captureController.requestCapture()
        }

        binding.btnStopCapture.setOnClickListener {
            log("Stopping screen capture…")
            captureController.stopCapture()
            updateCaptureButtons(running = false)
        }

        binding.btnScreenshot.setOnClickListener {
            log("Requesting screenshot…")
            captureController.requestScreenshot()
        }

        binding.btnClearLog.setOnClickListener {
            logBuffer.clear()
            binding.tvLog.text = ""
        }

        binding.btnGestureTap.setOnClickListener {
            val svc = AppAccessibilityService.instance
            if (svc != null) {
                val cx = binding.root.width / 2f
                val cy = binding.root.height / 2f
                svc.performTap(cx, cy)
                log("Performed tap at (${cx.toInt()}, ${cy.toInt()})")
            } else {
                log("Accessibility service not connected")
            }
        }

        binding.btnCollectText.setOnClickListener {
            val svc = AppAccessibilityService.instance
            if (svc != null) {
                lifecycleScope.launch {
                    val texts = withContext(Dispatchers.Default) { svc.collectAllText() }
                    log("Collected ${texts.size} text nodes:\n${texts.joinToString("\n")}")
                }
            } else {
                log("Accessibility service not connected")
            }
        }
    }

    // ── Status helpers ─────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val a11yEnabled = isAccessibilityEnabled()
        binding.tvA11yStatus.text =
            if (a11yEnabled) getString(R.string.status_a11y_enabled)
            else getString(R.string.status_a11y_disabled)
        binding.tvA11yStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (a11yEnabled) R.color.status_ok else R.color.status_error
            )
        )
        binding.btnAccessibility.text =
            if (a11yEnabled) getString(R.string.btn_disable_a11y)
            else getString(R.string.btn_enable_a11y)
    }

    private fun updateCaptureButtons(running: Boolean) {
        binding.btnStartCapture.isEnabled = !running
        binding.btnStopCapture.isEnabled = running
        binding.btnScreenshot.isEnabled = running
        binding.tvCaptureStatus.text =
            if (running) getString(R.string.status_capture_active)
            else getString(R.string.status_capture_idle)
        binding.tvCaptureStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.status_ok else R.color.status_error
            )
        )
    }

    // ── Accessibility helpers ──────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val componentName = "${packageName}/${AppAccessibilityService::class.java.canonicalName}"
        return enabledServices.contains(componentName)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        log("Opened Accessibility Settings")
    }

    private fun showDisableAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_disable_a11y_title)
            .setMessage(R.string.dialog_disable_a11y_msg)
            .setPositiveButton(R.string.dialog_open_settings) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ── Screenshot callback ────────────────────────────────────────────────────

    private fun onScreenshotSaved(path: String) {
        val file = File(path)
        val size = file.length() / 1024
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        log("[$stamp] Screenshot saved: ${file.name} (${size} KB)")
        updateCaptureButtons(running = true)
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    private fun log(message: String) {
        logBuffer.appendLine(message)
        binding.tvLog.text = logBuffer.toString()
        // Auto-scroll to bottom
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }
}
