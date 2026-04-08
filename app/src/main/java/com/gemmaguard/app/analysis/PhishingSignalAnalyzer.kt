package com.gemmaguard.app.analysis

import com.gemmaguard.app.model.PhishingAnalysisResult
import com.gemmaguard.app.model.RiskLevel
import kotlin.math.max
import kotlin.math.min

class PhishingSignalAnalyzer {
    private val urgencyRegex =
        Regex("\\b(urgent|immediately|immediate|hurry|asap|now|final warning|act now|verify now|action required)\\b")
    private val moneyRegex =
        Regex("\\b(send|wire|transfer|payment|paypal|venmo|zelle|cash ?app|gift card|bitcoin|crypto)\\b|\\$\\s?\\d+")
    private val credentialRegex =
        Regex("\\b(password|passcode|verification code|otp|login|log in|sign in|account|bank account|ssn)\\b")
    private val linkRegex =
        Regex("(?i)(https?://\\S+|www\\.\\S+|\\b[a-z0-9][a-z0-9.-]+\\.(com|net|org|io|co|app|xyz|top|link|site|ru|cn)\\S*)")
    private val brandRegex =
        Regex("\\b(paypal|apple|google|gmail|bank|chase|wells fargo|amazon|microsoft|support)\\b")
    private val pressureRegex =
        Regex("\\b(suspend|disabled|locked|expires|limited time|avoid|prevent|failure)\\b")

    fun analyze(ocrText: String): PhishingAnalysisResult {
        val normalizedText = ocrText.trim()
        if (normalizedText.isBlank()) {
            return PhishingAnalysisResult(
                riskLevel = RiskLevel.UNKNOWN,
                confidence = 0,
                reasons = emptyList(),
                recommendation = "Capture a clearer screen before making a decision.",
            )
        }

        val lowercaseText = normalizedText.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0

        if (urgencyRegex.containsMatchIn(lowercaseText) || pressureRegex.containsMatchIn(lowercaseText)) {
            reasons += "Uses urgent or high-pressure language."
            score += 22
        }

        if (moneyRegex.containsMatchIn(lowercaseText)) {
            reasons += "Requests money, payment, or a peer-to-peer transfer."
            score += 28
        }

        if (credentialRegex.containsMatchIn(lowercaseText)) {
            reasons += "Mentions credentials, account access, or verification details."
            score += 30
        }

        val links = linkRegex.findAll(lowercaseText).map { it.value }.toList()
        if (links.isNotEmpty()) {
            reasons += "Contains a link or domain that should be verified carefully."
            score += 20
            if (links.any { looksSuspicious(it) }) {
                reasons += "The detected link or domain has suspicious formatting."
                score += 15
            }
        }

        if (brandRegex.containsMatchIn(lowercaseText) && (links.isNotEmpty() || credentialRegex.containsMatchIn(lowercaseText))) {
            reasons += "Uses brand or account language that could be impersonation."
            score += 15
        }

        val riskLevel = when {
            score >= 60 -> RiskLevel.HIGH
            score >= 30 -> RiskLevel.MEDIUM
            score > 0 -> RiskLevel.LOW
            else -> RiskLevel.LOW
        }

        val recommendation = when (riskLevel) {
            RiskLevel.HIGH -> "Do not reply or pay. Verify the request in the official app or through a trusted contact."
            RiskLevel.MEDIUM -> "Pause and verify the request through an official channel before taking action."
            RiskLevel.LOW -> "Stay cautious and verify key details if anything feels unusual."
            RiskLevel.UNKNOWN -> "Capture a clearer screen before making a decision."
        }

        return PhishingAnalysisResult(
            riskLevel = riskLevel,
            confidence = computeConfidence(score = score, reasonCount = reasons.size),
            reasons = reasons.ifEmpty { listOf("No strong phishing signals were detected in the OCR text.") },
            recommendation = recommendation,
        )
    }

    private fun looksSuspicious(linkOrDomain: String): Boolean {
        val domain = linkOrDomain
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')

        return domain.any { it.isDigit() } ||
            domain.count { it == '-' } >= 2 ||
            domain.endsWith(".xyz") ||
            domain.endsWith(".top") ||
            domain.endsWith(".link") ||
            domain.endsWith(".ru") ||
            domain.endsWith(".cn")
    }

    private fun computeConfidence(score: Int, reasonCount: Int): Int {
        if (score == 0) {
            return 35
        }

        return min(95, max(45, score + (reasonCount * 6)))
    }
}
