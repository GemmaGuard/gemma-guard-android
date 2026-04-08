package com.gemmaguard.app.analysis

import android.graphics.Bitmap

data class PhishingAnalysisInput(
    val screenshot: Bitmap?,
    val ocrText: String,
)
