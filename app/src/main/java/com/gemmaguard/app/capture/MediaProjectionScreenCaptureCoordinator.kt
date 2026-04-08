package com.gemmaguard.app.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.core.content.ContextCompat

class MediaProjectionScreenCaptureCoordinator(
    private val context: Context,
) : ScreenCaptureCoordinator {
    private val mediaProjectionManager = context.getSystemService(MediaProjectionManager::class.java)

    override fun createConsentIntent(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun startCaptureSession(resultCode: Int, resultData: Intent) {
        ContextCompat.startForegroundService(
            context,
            ScreenCaptureService.createStartSessionIntent(
                context = context,
                resultCode = resultCode,
                resultData = resultData,
            ),
        )
    }

    override fun stopCaptureSession() {
        ContextCompat.startForegroundService(
            context,
            ScreenCaptureService.createStopSessionIntent(context),
        )
    }
}
