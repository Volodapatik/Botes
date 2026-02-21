package com.example.accessprojector.mediaprojection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.accessprojector.R
import com.example.accessprojector.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCaptureService is a foreground service that uses MediaProjection to
 * capture the screen and save screenshots on demand.
 *
 * Start with:
 * ```kotlin
 * val intent = ScreenCaptureService.buildIntent(context, resultCode, data)
 * ContextCompat.startForegroundService(context, intent)
 * ```
 */
class ScreenCaptureService : Service() {

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "ScreenCaptureSvc"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CHANNEL_NAME = "Screen Capture"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Action: take a single screenshot and broadcast the file path. */
        const val ACTION_TAKE_SCREENSHOT = "com.example.accessprojector.TAKE_SCREENSHOT"

        /** Action: stop the service and release MediaProjection. */
        const val ACTION_STOP = "com.example.accessprojector.STOP_CAPTURE"

        /** Broadcast action emitted after a screenshot is saved. */
        const val ACTION_SCREENSHOT_TAKEN = "com.example.accessprojector.SCREENSHOT_TAKEN"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"

        fun buildIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val handler = Handler(Looper.getMainLooper())
    private val isCapturing = AtomicBoolean(false)

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped externally")
            stopCapture()
        }
    }

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        resolveScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TAKE_SCREENSHOT -> {
                takeScreenshot()
                return START_STICKY
            }
        }

        // Initial start – set up MediaProjection
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Missing MediaProjection result data – stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        initMediaProjection(resultCode, resultData)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ── MediaProjection setup ──────────────────────────────────────────────────

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data).also { mp ->
            mp.registerCallback(mediaProjectionCallback, handler)
        }

        setupVirtualDisplay()
        isCapturing.set(true)
        Log.i(TAG, "Screen capture started (${screenWidth}x${screenHeight} @$screenDensity dpi)")
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            2                           // maxImages buffer
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
    }

    private fun stopCapture() {
        isCapturing.set(false)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "Screen capture stopped")
    }

    // ── Screenshot logic ───────────────────────────────────────────────────────

    /**
     * Acquires the latest frame from [ImageReader], converts it to a [Bitmap],
     * saves it as a PNG in [getExternalFilesDir], and broadcasts the path.
     */
    fun takeScreenshot() {
        if (!isCapturing.get()) {
            Log.w(TAG, "Cannot take screenshot – capture not active")
            return
        }

        val reader = imageReader ?: return
        val image: Image? = reader.acquireLatestImage()

        if (image == null) {
            Log.w(TAG, "No image available from ImageReader")
            return
        }

        try {
            val bitmap = imageToBitmap(image)
            val path = saveBitmap(bitmap)
            bitmap.recycle()
            broadcastScreenshot(path)
            Log.i(TAG, "Screenshot saved: $path")
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop away row padding if any
        return if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val dir = getExternalFilesDir("screenshots") ?: filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "screenshot_$stamp.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    private fun broadcastScreenshot(path: String) {
        val intent = Intent(ACTION_SCREENSHOT_TAKEN).apply {
            putExtra(EXTRA_SCREENSHOT_PATH, path)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun resolveScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen capture is active"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(getString(R.string.notification_capture_text))
            .setContentIntent(openPendingIntent)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.action_stop_capture),
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
