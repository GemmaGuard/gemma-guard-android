package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemma4ResponseParserTest {
    private val parser = Gemma4ResponseParser()

    @Test
    fun parse_acceptsJsonResponse() {
        val result = parser.parse(
            """
            {
              "risk": "High",
              "confidence": 87,
              "reasons": [
                "Uses urgent language.",
                "Requests account verification.",
                "Contains a suspicious domain."
              ],
              "recommendation": "Do not tap the link."
            }
            """.trimIndent(),
        )

        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertEquals(87, result.confidence)
        assertEquals(3, result.reasons.size)
        assertEquals("Do not tap the link.", result.recommendation)
    }

    @Test
    fun parse_fallsBackToLabeledSections() {
        val result = parser.parse(
            """
            Risk: Medium
            Confidence: 62
            Reasons:
            - Uses urgent language.
            - Requests money.
            Recommendation: Verify with the sender in the official app.
            """.trimIndent(),
        )

        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
        assertEquals(62, result.confidence)
        assertTrue(result.reasons.any { it.contains("urgent", ignoreCase = true) })
    }
}
