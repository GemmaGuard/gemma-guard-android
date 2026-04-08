package com.gemmaguard.app.ocr

class OcrTextFormatter {
    fun format(rawText: String): String {
        return rawText
            .lineSequence()
            .map { line -> line.trim() }
            .fold(mutableListOf<String>()) { lines, line ->
                if (line.isEmpty() && lines.lastOrNull()?.isEmpty() == true) {
                    lines
                } else {
                    lines.add(line)
                    lines
                }
            }
            .joinToString(separator = "\n")
            .trim()
    }
}
