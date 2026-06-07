package com.gemmaguard.app.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhishingPromptBuilderTest {
    @Test
    fun buildBundle_includesOcrTextAndJsonFormatInstructions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundle = PhishingPromptBuilder(context).buildBundle("Reset your password at paypa1.example")

        assertTrue(bundle.userPrompt.contains("paypa1.example"))
        assertTrue(bundle.userPrompt.contains("Return JSON only"))
        assertTrue(bundle.userPrompt.contains("\"risk\""))
    }
}
