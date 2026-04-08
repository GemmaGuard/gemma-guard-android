package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhishingSignalAnalyzerTest {
    private val analyzer = PhishingSignalAnalyzer()

    @Test
    fun analyze_flagsUrgentMoneyRequestAsHighRisk() {
        val result = analyzer.analyze("Hurry! Send me \$2000 via PayPal right now.")

        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.reasons.any { it.contains("urgent", ignoreCase = true) })
        assertTrue(result.reasons.any { it.contains("money", ignoreCase = true) })
    }

    @Test
    fun analyze_flagsCredentialLinkMessageAsHighRisk() {
        val result = analyzer.analyze("Verify now at paypa1-secure-login.xyz and enter your password.")

        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.reasons.any { it.contains("link", ignoreCase = true) })
        assertTrue(result.reasons.any { it.contains("credentials", ignoreCase = true) })
    }

    @Test
    fun analyze_returnsUnknownForBlankText() {
        val result = analyzer.analyze("   ")

        assertEquals(RiskLevel.UNKNOWN, result.riskLevel)
        assertTrue(result.reasons.isEmpty())
    }
}
