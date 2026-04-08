package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.PhishingAnalysisResult
import com.gemmaguard.app.model.RiskLevel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class Gemma4ResponseParser {
    fun parse(rawResponse: String): PhishingAnalysisResult {
        val normalizedResponse = rawResponse.trim()
        require(normalizedResponse.isNotEmpty()) { "Gemma returned an empty response." }

        return parseJsonLikePayload(normalizedResponse) ?: parseLabeledSections(normalizedResponse)
    }

    private fun parseJsonLikePayload(response: String): PhishingAnalysisResult? {
        val jsonCandidate = extractJsonObject(response) ?: return null

        return try {
            val json = JSONObject(jsonCandidate)
            PhishingAnalysisResult(
                riskLevel = parseRiskLevel(json.optString("risk")),
                confidence = parseConfidence(json.opt("confidence")),
                reasons = parseReasons(json.opt("reasons")),
                recommendation = parseRecommendation(json.optString("recommendation")),
            )
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseLabeledSections(response: String): PhishingAnalysisResult {
        val riskLevel = parseRiskLevel(findField(response, "Risk"))
        val confidence = parseConfidence(findField(response, "Confidence"))
        val reasons = parseBulletReasons(response)
        val recommendation = parseRecommendation(findField(response, "Recommendation"))

        return PhishingAnalysisResult(
            riskLevel = riskLevel,
            confidence = confidence,
            reasons = reasons,
            recommendation = recommendation,
        )
    }

    private fun extractJsonObject(response: String): String? {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start == -1 || end <= start) {
            return null
        }
        return response.substring(start, end + 1)
    }

    private fun parseRiskLevel(rawValue: String?): RiskLevel {
        return when (rawValue?.trim()?.lowercase()) {
            "low" -> RiskLevel.LOW
            "medium" -> RiskLevel.MEDIUM
            "high" -> RiskLevel.HIGH
            else -> throw IllegalArgumentException("Gemma response did not include a valid risk level.")
        }
    }

    private fun parseConfidence(rawValue: Any?): Int {
        val numericValue = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.filter { it.isDigit() }.toIntOrNull()
            else -> null
        } ?: throw IllegalArgumentException("Gemma response did not include a valid confidence score.")

        return numericValue.coerceIn(0, 100)
    }

    private fun parseReasons(rawValue: Any?): List<String> {
        val reasons = when (rawValue) {
            is JSONArray -> buildList {
                for (index in 0 until rawValue.length()) {
                    rawValue.optString(index)
                        .trim()
                        .trimStart('-', '*', '•')
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(::add)
                }
            }

            is String -> rawValue
                .lineSequence()
                .map { it.trim().trimStart('-', '*', '•').trim() }
                .filter { it.isNotEmpty() }
                .toList()

            else -> emptyList()
        }

        require(reasons.isNotEmpty()) { "Gemma response did not include any reasons." }
        return reasons.take(3)
    }

    private fun parseRecommendation(rawValue: String?): String {
        val recommendation = rawValue?.trim().orEmpty()
        require(recommendation.isNotEmpty()) { "Gemma response did not include a recommendation." }
        return recommendation
    }

    private fun parseBulletReasons(response: String): List<String> {
        val reasonsSection = response.substringAfter("Reasons:", missingDelimiterValue = "")
            .substringBefore("Recommendation:")
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") }
            .map { it.trimStart('-', '•', '*').trim() }
            .filter { it.isNotEmpty() }
            .toList()

        require(reasonsSection.isNotEmpty()) { "Gemma response did not include bullet reasons." }
        return reasonsSection.take(3)
    }

    private fun findField(response: String, fieldName: String): String {
        val regex = Regex("$fieldName:\\s*(.+)", RegexOption.IGNORE_CASE)
        return regex.find(response)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Gemma response did not include $fieldName.")
    }
}
