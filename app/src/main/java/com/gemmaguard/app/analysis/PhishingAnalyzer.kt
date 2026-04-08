package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.PhishingAnalysisResult

interface PhishingAnalyzer {
    suspend fun analyze(input: PhishingAnalysisInput): PhishingAnalysisResult
}
