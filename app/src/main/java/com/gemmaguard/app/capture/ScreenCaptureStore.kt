package com.gemmaguard.app.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScreenCaptureStore {
    private val mutableState = MutableStateFlow<ScreenCaptureUiState>(ScreenCaptureUiState.Idle)
    val state: StateFlow<ScreenCaptureUiState> = mutableState

    fun setIdle() {
        updateState(ScreenCaptureUiState.Idle)
    }

    fun setArmed() {
        updateState(ScreenCaptureUiState.Armed)
    }

    fun setCapturing() {
        updateState(ScreenCaptureUiState.Capturing)
    }

    fun publishCaptured(bitmap: Bitmap) {
        updateState(ScreenCaptureUiState.Captured(bitmap))
    }

    fun publishError(message: String) {
        updateState(ScreenCaptureUiState.Error(message))
    }

    fun clearCapturedBitmap(bitmap: Bitmap? = null) {
        val currentState = mutableState.value
        if (currentState is ScreenCaptureUiState.Captured &&
            (bitmap == null || currentState.bitmap === bitmap)
        ) {
            mutableState.value = ScreenCaptureUiState.Idle
        }
    }

    private fun updateState(nextState: ScreenCaptureUiState) {
        mutableState.value = nextState
    }
}
