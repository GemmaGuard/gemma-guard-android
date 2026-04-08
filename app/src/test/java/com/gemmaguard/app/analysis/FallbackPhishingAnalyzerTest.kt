package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.PhishingAnalysisResult
import com.gemmaguard.app.model.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackPhishingAnalyzerTest {
    @Test
    fun analyze_usesFallbackWhenPrimaryFails() {
        val analyzer = FallbackPhishingAnalyzer(
            primary = object : PhishingAnalyzer {
                override suspend fun analyze(input: PhishingAnalysisInput): PhishingAnalysisResult {
                    error("Gemma unavailable")
                }
            },
            fallback = object : PhishingAnalyzer {
                override suspend fun analyze(input: PhishingAnalysisInput): PhishingAnalysisResult {
                    return PhishingAnalysisResult(
                        riskLevel = RiskLevel.MEDIUM,
                        confidence = 61,
                        reasons = listOf("Fallback analyzer used."),
                        recommendation = "Verify independently.",
                    )
                }
            },
        )

        val result = runBlocking {
            analyzer.analyze(
                PhishingAnalysisInput(
                    screenshot = null,
                    ocrText = "test",
                ),
            )
        }

        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
        assertEquals("Verify independently.", result.recommendation)
    }
}
