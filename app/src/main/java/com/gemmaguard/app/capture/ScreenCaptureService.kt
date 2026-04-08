package com.gemmaguard.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.gemmaguard.app.R
import com.gemmaguard.app.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureDensity: Int = 0
    private var reopenAppAfterCapture: Boolean = false
    private val sessionReleased = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)
    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
                stopCaptureSession(resetToIdle = false)
                stopSelf()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession(intent)
            ACTION_CAPTURE_NOW -> captureNow(intent.getBooleanExtra(EXTRA_REOPEN_APP, false))
            ACTION_STOP_SESSION -> {
                ScreenCaptureStore.publishError(getString(R.string.capture_session_stopped_status))
                stopCaptureSession(resetToIdle = false)
                stopSelf()
            }

            else -> {
                ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
                stopCaptureSession(resetToIdle = false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCaptureSession(resetToIdle = false)
        super.onDestroy()
    }

    private fun startSession(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.getIntentExtra(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(mode = NotificationMode.Armed),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        val windowManager = getSystemService(WindowManager::class.java)
        if (mediaProjectionManager == null || windowManager == null) {
            Log.w(TAG, "Required system services were unavailable for screen capture.")
            ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
            stopCaptureSession(resetToIdle = false)
            stopSelf()
            return
        }
        val bounds = windowManager.maximumWindowMetrics.bounds
        captureWidth = bounds.width()
        captureHeight = bounds.height()
        captureDensity = resources.displayMetrics.densityDpi

        captureThread = HandlerThread("GemmaGuardScreenCapture").also { thread ->
            thread.start()
            captureHandler = Handler(thread.looper)
        }

        sessionReleased.set(false)
        captureInFlight.set(false)

        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        } catch (_: SecurityException) {
            ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
            stopCaptureSession(resetToIdle = false)
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(projectionCallback, captureHandler)
        if (!Settings.canDrawOverlays(this)) {
            ScreenCaptureStore.publishError(getString(R.string.overlay_permission_denied_status))
            stopCaptureSession(resetToIdle = false)
            stopSelf()
            return
        }
        startOverlay()
        ScreenCaptureStore.setArmed()
    }

    private fun captureNow(reopenAppAfterCapture: Boolean) {
        val projection = mediaProjection
        val handler = captureHandler
        if (projection == null || handler == null) {
            ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
            stopCaptureSession(resetToIdle = false)
            stopSelf()
            return
        }
        if (!captureInFlight.compareAndSet(false, true)) {
            return
        }

        this.reopenAppAfterCapture = reopenAppAfterCapture
        stopOverlay()
        ScreenCaptureStore.setCapturing()
        updateNotification(mode = NotificationMode.Capturing)

        handler.postDelayed(
            {
                prepareCaptureResources()
                val captureSurface = imageReader?.surface
                if (captureSurface == null) {
                    Log.w(TAG, "ImageReader surface was unavailable for screen capture.")
                    ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
                    stopCaptureSession(resetToIdle = false)
                    stopSelf()
                    return@postDelayed
                }
                try {
                    virtualDisplay = projection.createVirtualDisplay(
                        "GemmaGuardCapture",
                        captureWidth,
                        captureHeight,
                        captureDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        captureSurface,
                        null,
                        handler,
                    )
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Failed to create virtual display for screen capture.", error)
                    ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
                    stopCaptureSession(resetToIdle = false)
                    stopSelf()
                }
            },
            CAPTURE_DELAY_MS,
        )
    }

    private fun prepareCaptureResources() {
        releaseCaptureResources()

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2,
        ).apply {
            setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    handleImage(image)
                },
                captureHandler,
            )
        }
    }

    private fun handleImage(image: Image) {
        image.use { capturedImage ->
            if (!captureInFlight.compareAndSet(true, false)) {
                return
            }

            val plane = capturedImage.planes.firstOrNull()
            if (plane == null) {
                ScreenCaptureStore.publishError(getString(R.string.capture_failed_status))
                stopCaptureSession(resetToIdle = false)
                stopSelf()
                return
            }

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - (pixelStride * captureWidth)
            val rawBitmap = Bitmap.createBitmap(
                captureWidth + (rowPadding / pixelStride),
                captureHeight,
                Bitmap.Config.ARGB_8888,
            )
            rawBitmap.copyPixelsFromBuffer(plane.buffer)
            val croppedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, captureWidth, captureHeight)
            rawBitmap.recycle()

            ScreenCaptureStore.publishCaptured(croppedBitmap)
            if (reopenAppAfterCapture) {
                launchMainActivity()
            }
            stopCaptureSession(resetToIdle = false)
            stopSelf()
        }
    }

    private fun updateNotification(mode: NotificationMode) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(mode))
    }

    private fun buildNotification(mode: NotificationMode): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)

        return when (mode) {
            NotificationMode.Armed ->
                builder
                    .setContentTitle(getString(R.string.capture_notification_title))
                    .setContentText(getString(R.string.capture_notification_text))
                    .build()

            NotificationMode.Capturing ->
                builder
                    .setContentTitle(getString(R.string.capture_notification_title))
                    .setContentText(getString(R.string.capture_notification_capturing_text))
                    .build()
        }
    }

    private fun stopCaptureSession(resetToIdle: Boolean) {
        if (!sessionReleased.compareAndSet(false, true)) {
            return
        }

        releaseCaptureResources()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        captureWidth = 0
        captureHeight = 0
        captureDensity = 0
        captureInFlight.set(false)
        reopenAppAfterCapture = false
        stopOverlay()

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (resetToIdle) {
            ScreenCaptureStore.setIdle()
        }
    }

    private fun releaseCaptureResources() {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.capture_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.capture_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun Intent.getIntentExtra(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    private enum class NotificationMode {
        Armed,
        Capturing,
    }

    private fun startOverlay() {
        startService(OverlayTriggerService.createShowIntent(this))
    }

    private fun stopOverlay() {
        stopService(OverlayTriggerService.createHideIntent(this))
    }

    private fun launchMainActivity() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "GemmaGuardCapture"
        private const val ACTION_START_SESSION = "com.gemmaguard.app.action.START_CAPTURE_SESSION"
        private const val ACTION_CAPTURE_NOW = "com.gemmaguard.app.action.CAPTURE_NOW"
        private const val ACTION_STOP_SESSION = "com.gemmaguard.app.action.STOP_CAPTURE_SESSION"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
        private const val REQUEST_OPEN_APP = 1
        private const val CAPTURE_DELAY_MS = 800L

        fun createStartSessionIntent(context: Context, resultCode: Int, resultData: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
        }

        fun createCaptureNowIntent(context: Context, reopenAppAfterCapture: Boolean): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE_NOW
                putExtra(EXTRA_REOPEN_APP, reopenAppAfterCapture)
            }
        }

        fun createStopSessionIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
        }

        private const val EXTRA_REOPEN_APP = "extra_reopen_app"
    }
}
