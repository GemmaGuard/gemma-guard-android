package com.gemmaguard.app.capture

import android.graphics.Bitmap

sealed interface ScreenCaptureUiState {
    data object Idle : ScreenCaptureUiState

    data object Armed : ScreenCaptureUiState

    data object Capturing : ScreenCaptureUiState

    data class Captured(val bitmap: Bitmap) : ScreenCaptureUiState

    data class Error(val message: String) : ScreenCaptureUiState
}
