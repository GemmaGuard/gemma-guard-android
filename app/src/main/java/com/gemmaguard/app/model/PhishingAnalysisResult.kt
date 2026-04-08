package com.gemmaguard.app.model

data class PhishingAnalysisResult(
    val riskLevel: RiskLevel,
    val confidence: Int,
    val reasons: List<String>,
    val recommendation: String,
)
