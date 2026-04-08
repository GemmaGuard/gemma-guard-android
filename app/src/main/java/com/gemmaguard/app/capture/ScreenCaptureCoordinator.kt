package com.gemmaguard.app.capture

import android.content.Intent

interface ScreenCaptureCoordinator {
    fun createConsentIntent(): Intent

    fun startCaptureSession(resultCode: Int, resultData: Intent)

    fun stopCaptureSession()
}
