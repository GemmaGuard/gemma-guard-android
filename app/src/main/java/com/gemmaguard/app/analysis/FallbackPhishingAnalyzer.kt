package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.PhishingAnalysisResult

class FallbackPhishingAnalyzer(
    private val primary: PhishingAnalyzer,
    private val fallback: PhishingAnalyzer,
) : PhishingAnalyzer {
    override suspend fun analyze(input: PhishingAnalysisInput): PhishingAnalysisResult {
        return runCatching {
            primary.analyze(input)
        }.getOrElse {
            fallback.analyze(input)
        }
    }
}
