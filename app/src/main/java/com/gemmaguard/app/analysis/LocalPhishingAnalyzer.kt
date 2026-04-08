package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.PhishingAnalysisResult

class LocalPhishingAnalyzer(
    private val signalAnalyzer: PhishingSignalAnalyzer = PhishingSignalAnalyzer(),
) : PhishingAnalyzer {
    override suspend fun analyze(input: PhishingAnalysisInput): PhishingAnalysisResult {
        return signalAnalyzer.analyze(input.ocrText)
    }
}
