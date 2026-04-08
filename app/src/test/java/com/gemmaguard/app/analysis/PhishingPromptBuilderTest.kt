package com.gemmaguard.app.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

class PhishingPromptBuilderTest {
    @Test
    fun buildBundle_includesOcrTextAndJsonFormatInstructions() {
        val bundle = PhishingPromptBuilder().buildBundle("Reset your password at paypa1.example")

        assertTrue(bundle.userPrompt.contains("paypa1.example"))
        assertTrue(bundle.userPrompt.contains("Return JSON only"))
        assertTrue(bundle.userPrompt.contains("\"risk\""))
    }
}
