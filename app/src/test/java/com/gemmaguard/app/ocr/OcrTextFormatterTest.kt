package com.gemmaguard.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextFormatterTest {
    @Test
    fun format_trimsLinesAndCollapsesRepeatedBlankRows() {
        val formatted = OcrTextFormatter().format(
            "  Verify now  \n\n\n  paypa1.example/login  \n   \n  Enter password ",
        )

        assertEquals(
            "Verify now\n\npaypa1.example/login\n\nEnter password",
            formatted,
        )
    }
}
