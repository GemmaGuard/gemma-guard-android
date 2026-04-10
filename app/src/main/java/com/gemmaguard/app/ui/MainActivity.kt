package com.gemmaguard.app.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import com.gemmaguard.app.analysis.Gemma4PhishingAnalyzer
import com.gemmaguard.app.analysis.LocalPhishingAnalyzer
import com.gemmaguard.app.analysis.PhishingAnalysisInput
import com.gemmaguard.app.capture.MediaProjectionScreenCaptureCoordinator
import com.gemmaguard.app.capture.ScreenCaptureStore
import com.gemmaguard.app.capture.ScreenCaptureUiState
import com.gemmaguard.app.databinding.ActivityMainBinding
import com.gemmaguard.app.databinding.DialogSharePreviewBinding
import com.gemmaguard.app.databinding.ItemScanHistoryBinding
import com.gemmaguard.app.model.PhishingAnalysisResult
import com.gemmaguard.app.model.RiskLevel
import com.gemmaguard.app.ocr.OcrTextFormatter
import com.gemmaguard.app.ocr.TextRecognitionEngine
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var screenCaptureCoordinator: MediaProjectionScreenCaptureCoordinator
    private lateinit var gemmaAnalyzer: Gemma4PhishingAnalyzer
    private val localPhishingAnalyzer = LocalPhishingAnalyzer()
    private val textRecognitionEngine = TextRecognitionEngine()
    private val ocrTextFormatter = OcrTextFormatter()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var currentCaptureState: ScreenCaptureUiState = ScreenCaptureUiState.Idle
    private var currentOcrJob: Job? = null
    private var uploadImageJob: Job? = null
    private var analysisTickerJob: Job? = null
    private var modelPreparationJob: Job? = null
    private var overlayMessageJob: Job? = null
    private var lastRecognizedBitmap: Bitmap? = null
    private var lastRecognizedText: String? = null
    private var isModelPreparing: Boolean = false
    private var isMoreInfoExpanded: Boolean = false
    private var isAdvancedInfoExpanded: Boolean = false
    private var currentAnalysisEntry: ScanHistoryEntry? = null
    private val scanHistory = ArrayDeque<ScanHistoryEntry>()

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultData = result.data
            if (result.resultCode == RESULT_OK && resultData != null) {
                screenCaptureCoordinator.startCaptureSession(
                    resultCode = result.resultCode,
                    resultData = resultData,
                )
                moveCaptureSetupToBackground()
            } else {
                ScreenCaptureStore.publishError(getString(com.gemmaguard.app.R.string.capture_denied_status))
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (screenCaptureCoordinator.canDrawOverlays()) {
                requestScreenCaptureConsent()
            } else {
                ScreenCaptureStore.publishError(
                    getString(com.gemmaguard.app.R.string.overlay_permission_denied_status),
                )
            }
        }

    private val uploadImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                analyzeUploadedImage(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        screenCaptureCoordinator = MediaProjectionScreenCaptureCoordinator(applicationContext)
        gemmaAnalyzer = Gemma4PhishingAnalyzer(applicationContext)

        binding.homeOrbButton.setOnClickListener(::onPrimaryActionClicked)
        binding.uploadImageButton.setOnClickListener { onUploadImageClicked() }
        binding.analysisMoreInfoButton.setOnClickListener { toggleMoreInfo() }
        binding.analysisTechnicalToggleButton.setOnClickListener { toggleAdvancedInfo() }
        binding.analysisShareButton.setOnClickListener { shareCurrentAnalysis() }
        binding.overlayDismissButton.setOnClickListener {
            binding.scanningOverlay.isVisible = false
            setHighRiskWarningVisible(false)
            binding.analysisCard.isVisible = true
        }
        binding.overlayShareButton.setOnClickListener { shareCurrentAnalysis() }
        binding.homeOrbView.setReadinessProgress(0f)
        renderModelPreparationIdle()
        renderHistory()
        renderAnalysisEmpty(
            status = getString(com.gemmaguard.app.R.string.analysis_status_idle),
        )
        ensureGemmaModelPrepared()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScreenCaptureStore.state.collect(::renderCaptureState)
            }
        }
    }

    override fun onDestroy() {
        currentOcrJob?.cancel()
        uploadImageJob?.cancel()
        analysisTickerJob?.cancel()
        modelPreparationJob?.cancel()
        overlayMessageJob?.cancel()
        gemmaAnalyzer.cancelActiveAnalysis()
        analysisExecutor.shutdownNow()
        gemmaAnalyzer.close()
        binding.analysisPreviewImage.setImageDrawable(null)
        if (isFinishing && !isChangingConfigurations) {
            scanHistory.forEach { it.deleteAssets() }
            scanHistory.clear()
            ScreenCaptureStore.clearCapturedBitmap(lastRecognizedBitmap)
            releaseLastRecognizedBitmap()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ensureGemmaModelPrepared()
    }

    private fun onPrimaryActionClicked(@Suppress("UNUSED_PARAMETER") view: android.view.View) {
        if (isModelPreparing) {
            return
        }
        when (currentCaptureState) {
            ScreenCaptureUiState.Idle,
            is ScreenCaptureUiState.Captured,
            is ScreenCaptureUiState.Error -> {
                ensureOverlayPermissionThenRequestCapture()
            }

            ScreenCaptureUiState.Armed -> {
                screenCaptureCoordinator.stopCaptureSession()
            }

            ScreenCaptureUiState.Capturing -> Unit
        }
    }

    private fun onUploadImageClicked() {
        if (isModelPreparing) {
            return
        }
        if (currentCaptureState == ScreenCaptureUiState.Armed ||
            currentCaptureState == ScreenCaptureUiState.Capturing
        ) {
            screenCaptureCoordinator.stopCaptureSession()
        }
        uploadImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun renderCaptureState(state: ScreenCaptureUiState) {
        currentCaptureState = state
        when (state) {
            ScreenCaptureUiState.Idle -> {
                binding.statusSummary.text = getString(com.gemmaguard.app.R.string.capture_ready_status)
                if (lastRecognizedBitmap == null) {
                    renderOcrEmpty()
                    renderAnalysisEmpty(
                        status = getString(com.gemmaguard.app.R.string.analysis_status_idle),
                    )
                }
            }

            ScreenCaptureUiState.Armed -> {
                binding.statusSummary.text = getString(com.gemmaguard.app.R.string.capture_armed_status)
            }

            ScreenCaptureUiState.Capturing -> {
                binding.statusSummary.text = getString(com.gemmaguard.app.R.string.capture_in_progress_status)
            }

            is ScreenCaptureUiState.Captured -> {
                binding.statusSummary.text = getString(com.gemmaguard.app.R.string.capture_complete_status)
                showPreview(bitmap = state.bitmap)
                analyzeCapturedText(state.bitmap)
            }

            is ScreenCaptureUiState.Error -> {
                binding.statusSummary.text = state.message
                if (lastRecognizedBitmap == null) {
                    renderAnalysisEmpty(
                        status = getString(com.gemmaguard.app.R.string.analysis_status_unavailable),
                    )
                }
            }
        }
    }

    private fun ensureOverlayPermissionThenRequestCapture() {
        if (screenCaptureCoordinator.canDrawOverlays()) {
            requestScreenCaptureConsent()
            return
        }

        binding.statusSummary.text =
            getString(com.gemmaguard.app.R.string.overlay_permission_requesting_status)
        val overlaySettingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayPermissionLauncher.launch(overlaySettingsIntent)
    }

    private fun requestScreenCaptureConsent() {
        binding.statusSummary.text = getString(com.gemmaguard.app.R.string.capture_requesting_permission)
        capturePermissionLauncher.launch(screenCaptureCoordinator.createConsentIntent())
    }

    private fun moveCaptureSetupToBackground() {
        if (!moveTaskToBack(true)) {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
        }
    }

    private fun showPreview(bitmap: Bitmap?) {
        binding.analysisPreviewImage.isVisible = bitmap != null
        binding.analysisPreviewEmpty.isVisible = bitmap == null
        binding.analysisPreviewImage.setImageBitmap(bitmap)
    }

    private fun analyzeUploadedImage(uri: Uri) {
        binding.statusSummary.text = getString(com.gemmaguard.app.R.string.upload_image_loading)
        currentOcrJob?.cancel()
        uploadImageJob?.cancel()
        analysisTickerJob?.cancel()
        gemmaAnalyzer.cancelActiveAnalysis()

        uploadImageJob = lifecycleScope.launch {
            runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    decodeBitmapFromUri(uri)
                }
            }.onSuccess { bitmap ->
                binding.statusSummary.text = getString(com.gemmaguard.app.R.string.upload_image_ready)
                ScreenCaptureStore.clearCapturedBitmap(lastRecognizedBitmap)
                showPreview(bitmap)
                analyzeCapturedText(bitmap)
            }.onFailure { error ->
                Log.e(TAG, "Failed to decode uploaded image.", error)
                binding.statusSummary.text = getString(com.gemmaguard.app.R.string.upload_image_error)
                renderAnalysisFailure(
                    userStatus = getString(com.gemmaguard.app.R.string.upload_image_error),
                    diagnostics = buildDiagnosticsMessage(
                        prefix = "Gemma Guard could not read the selected image.",
                        throwable = error,
                    ),
                )
            }
        }
    }

    private fun analyzeCapturedText(bitmap: Bitmap) {
        if (lastRecognizedBitmap === bitmap) {
            return
        }

        currentOcrJob?.cancel()
        analysisTickerJob?.cancel()
        gemmaAnalyzer.cancelActiveAnalysis()
        replaceLastRecognizedBitmap(bitmap)
        lastRecognizedText = null
        isMoreInfoExpanded = false
        isAdvancedInfoExpanded = false
        renderOcrLoading()
        renderAnalysisLoading()
        currentOcrJob = lifecycleScope.launch {
            runCatching {
                ocrTextFormatter.format(textRecognitionEngine.recognize(bitmap))
            }.onSuccess { formattedText ->
                if (formattedText.isBlank()) {
                    renderOcrEmptyResult()
                    renderAnalysisEmpty(
                        status = getString(com.gemmaguard.app.R.string.analysis_status_unavailable),
                    )
                } else {
                    lastRecognizedText = formattedText
                    renderOcrText(formattedText)
                    val input = PhishingAnalysisInput(
                        screenshot = bitmap,
                        ocrText = formattedText,
                    )
                    analyzePhishingRisk(input)
                }
            }.onFailure {
                Log.e(TAG, "OCR pipeline failed.", it)
                renderOcrError()
                renderAnalysisFailure(
                    userStatus = getString(com.gemmaguard.app.R.string.analysis_status_unavailable),
                    diagnostics = buildDiagnosticsMessage(
                        prefix = "OCR failed before Gemma analysis could start.",
                        throwable = it,
                    ),
                )
            }
        }
    }

    private suspend fun analyzePhishingRisk(input: PhishingAnalysisInput) {
        prepareGemmaModel()
        startAnalysisTicker()
        val gemmaAttempt = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val future = analysisExecutor.submit<PhishingAnalysisResult> {
                        gemmaAnalyzer.analyzeBlocking(input)
                    }

                    try {
                        future.get(ANALYSIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        gemmaAnalyzer.cancelActiveAnalysis()
                        future.cancel(true)
                        throw AnalysisTimeoutException(
                            "Gemma 4 did not finish within ${ANALYSIS_TIMEOUT_SECONDS}s.",
                        )
                    }
                }
            }
        } finally {
            stopAnalysisTicker()
        }

        gemmaAttempt.onSuccess { result ->
            val completedAt = System.currentTimeMillis()
            renderAnalysisResult(
                result = result,
                source = AnalysisSource.GEMMA4,
                timestampMillis = completedAt,
            )
            renderAnalysisDiagnostics(
                title = null,
                details = null,
            )
            appendHistoryEntry(
                screenshot = input.screenshot,
                ocrText = input.ocrText,
                result = result,
                source = AnalysisSource.GEMMA4,
                timestampMillis = completedAt,
            )
        }.onFailure { throwable ->
            Log.e(TAG, "Gemma 4 analysis failed. Falling back to local analyzer.", throwable)
            val fallbackResult = localPhishingAnalyzer.analyze(input)
            val completedAt = System.currentTimeMillis()
            renderAnalysisResult(
                result = fallbackResult,
                source = AnalysisSource.LOCAL_FALLBACK,
                timestampMillis = completedAt,
            )
            renderAnalysisDiagnostics(
                title = getString(com.gemmaguard.app.R.string.analysis_diagnostics_title),
                details = buildDiagnosticsMessage(
                    prefix = getString(com.gemmaguard.app.R.string.analysis_fallback_details),
                    throwable = throwable,
                ),
            )
            appendHistoryEntry(
                screenshot = input.screenshot,
                ocrText = input.ocrText,
                result = fallbackResult,
                source = AnalysisSource.LOCAL_FALLBACK,
                timestampMillis = completedAt,
            )
        }
    }

    private suspend fun prepareGemmaModel() {
        renderModelPreparationStarted()
        var preparationReady = false
        runCatching {
            gemmaAnalyzer.prepareModelForUse { copiedBytes, totalBytes ->
                val progress = ((copiedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                runOnUiThread {
                    renderModelPreparationProgress(progress)
                }
            }
            gemmaAnalyzer.modelAvailability().activeLocation != null
        }.onSuccess { isReady ->
            preparationReady = isReady
        }.onFailure { throwable ->
            Log.w(TAG, "Gemma 4 model preparation did not complete.", throwable)
            runOnUiThread {
                renderModelPreparationFailure(throwable)
            }
        }
        runOnUiThread {
            if (preparationReady) {
                renderModelPreparationReady()
            } else {
                renderModelPreparationFailure(
                    IllegalStateException("No staged Gemma 4 model was found to prepare."),
                )
            }
        }
    }

    private fun ensureGemmaModelPrepared() {
        if (modelPreparationJob?.isActive == true) {
            return
        }
        modelPreparationJob = lifecycleScope.launch {
            prepareGemmaModel()
        }
    }

    private fun renderModelPreparationIdle() {
        isModelPreparing = false
        binding.modelPreparationTitle.isVisible = false
        binding.modelPreparationProgress.isVisible = false
        binding.modelPreparationLabel.isVisible = false
        binding.homeOrbButton.isEnabled = true
        binding.homeOrbButton.alpha = 1f
        binding.uploadImageButton.isEnabled = true
        binding.uploadImageButton.alpha = 1f
        binding.homeOrbView.setReadinessProgress(1f)
    }

    private fun renderModelPreparationStarted() {
        isModelPreparing = true
        binding.modelPreparationTitle.isVisible = true
        binding.modelPreparationProgress.isVisible = true
        binding.modelPreparationLabel.isVisible = true
        binding.modelPreparationProgress.isIndeterminate = true
        binding.modelPreparationLabel.setTextColor(
            ContextCompat.getColor(this, com.gemmaguard.app.R.color.inkMuted),
        )
        binding.modelPreparationLabel.text =
            getString(com.gemmaguard.app.R.string.model_preparation_status_starting)
        binding.homeOrbButton.isEnabled = false
        binding.homeOrbButton.alpha = 0.8f
        binding.uploadImageButton.isEnabled = false
        binding.uploadImageButton.alpha = 0.65f
        binding.homeOrbView.setReadinessProgress(0f)
    }

    private fun renderModelPreparationProgress(progress: Int) {
        isModelPreparing = true
        binding.modelPreparationTitle.isVisible = true
        binding.modelPreparationProgress.isVisible = true
        binding.modelPreparationLabel.isVisible = true
        binding.modelPreparationProgress.isIndeterminate = false
        binding.modelPreparationProgress.progress = progress
        binding.modelPreparationLabel.setTextColor(
            ContextCompat.getColor(this, com.gemmaguard.app.R.color.inkMuted),
        )
        binding.modelPreparationLabel.text = getString(
            com.gemmaguard.app.R.string.model_preparation_status_progress,
            progress,
        )
        binding.homeOrbButton.isEnabled = false
        binding.homeOrbButton.alpha = 0.8f
        binding.uploadImageButton.isEnabled = false
        binding.uploadImageButton.alpha = 0.65f
        binding.homeOrbView.setReadinessProgress(progress / 100f)
    }

    private fun renderModelPreparationReady() {
        renderModelPreparationIdle()
        binding.homeOrbView.setReadinessProgress(1f)
    }

    private fun renderModelPreparationFailure(throwable: Throwable) {
        isModelPreparing = false
        binding.modelPreparationTitle.isVisible = true
        binding.modelPreparationProgress.isVisible = false
        binding.modelPreparationLabel.isVisible = true
        binding.modelPreparationLabel.text = modelPreparationFailureMessage(throwable)
        binding.modelPreparationLabel.setTextColor(
            ContextCompat.getColor(this, com.gemmaguard.app.R.color.riskMedium),
        )
        binding.homeOrbButton.isEnabled = true
        binding.homeOrbButton.alpha = 1f
        binding.uploadImageButton.isEnabled = true
        binding.uploadImageButton.alpha = 1f
        binding.homeOrbView.setReadinessProgress(0.12f)
    }

    private fun renderOcrLoading() {
        binding.analysisOcrStatus.text = getString(com.gemmaguard.app.R.string.ocr_status_loading)
        binding.analysisOcrText.isVisible = false
        binding.analysisOcrEmpty.isVisible = false
    }

    private fun renderOcrText(text: String) {
        binding.analysisOcrStatus.text = getString(com.gemmaguard.app.R.string.ocr_status_complete)
        binding.analysisOcrText.isVisible = true
        binding.analysisOcrEmpty.isVisible = false
        binding.analysisOcrText.text = cleanOcrTextForDisplay(text)
    }

    private fun renderOcrEmpty() {
        binding.analysisOcrStatus.text = getString(com.gemmaguard.app.R.string.ocr_status_idle)
        binding.analysisOcrText.isVisible = false
        binding.analysisOcrEmpty.isVisible = true
        binding.analysisOcrEmpty.text = getString(com.gemmaguard.app.R.string.ocr_empty_state)
    }

    private fun renderOcrEmptyResult() {
        binding.analysisOcrStatus.text = getString(com.gemmaguard.app.R.string.ocr_status_empty_result)
        binding.analysisOcrText.isVisible = false
        binding.analysisOcrEmpty.isVisible = true
        binding.analysisOcrEmpty.text = getString(com.gemmaguard.app.R.string.ocr_empty_result)
    }

    private fun renderOcrError() {
        binding.analysisOcrStatus.text = getString(com.gemmaguard.app.R.string.ocr_status_error)
        binding.analysisOcrText.isVisible = false
        binding.analysisOcrEmpty.isVisible = true
        binding.analysisOcrEmpty.text = getString(com.gemmaguard.app.R.string.ocr_error_message)
    }

    private fun renderAnalysisResult(
        result: PhishingAnalysisResult,
        source: AnalysisSource,
        timestampMillis: Long,
    ) {
        val riskColors = riskPaletteFor(result.riskLevel)
        val isHighRisk = result.riskLevel == RiskLevel.HIGH
        val displayReasons = result.reasons.map(::sanitizeDisplayText)
        val displaySummary = displayReasons.firstOrNull()
            ?: sanitizeDisplayText(result.recommendation)
        val summaryLine = if (isHighRisk) {
            shareBodyFor(result.riskLevel)
        } else {
            displaySummary.ifBlank { shareBodyFor(result.riskLevel) }
        }
        val conciseRecommendation = conciseRecommendationFor(result)
        val detailReasons = displayReasons
            .filterNot { isDuplicateSummaryReason(reason = it, summary = summaryLine) }
        applyAnalysisCardColors(riskColors)
        setHighRiskWarningVisible(isHighRisk)
        stopOverlayMessageCycle()
        binding.overlayScanningSection.isVisible = false
        binding.overlayResultBanner.isVisible = true
        binding.overlayResultSection.isVisible = true
        binding.overlayResultBanner.text = bannerLabelFor(result.riskLevel)
        binding.overlayResultBanner.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(getColor(riskColors.accentColorRes))
        }
        binding.overlayWarningIcon.isVisible = isHighRisk
        binding.overlayConfidenceRow.isVisible = false
        binding.overlayResultConfidence.text = formatConfidenceValue(result.confidence)
        binding.overlayResultConfidenceContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(com.gemmaguard.app.R.color.panel))
            setStroke((resources.displayMetrics.density * 5).toInt(), getColor(com.gemmaguard.app.R.color.accentSurface))
        }
        binding.overlayResultSource.text = sourceLabelFor(source)
        binding.overlayResultSummary.maxLines = 4
        binding.overlayResultSummary.text = if (isHighRisk) {
            conciseRecommendation
        } else {
            summaryLine
        }
        binding.overlayResultReasonsTitle.isVisible = false
        binding.overlayResultReasons.isVisible = false
        binding.overlayResultReasons.text = detailReasons.take(3).joinToString("\n") { "• $it" }
        binding.overlayResultRecommendation.isVisible = false
        binding.overlayResultRecommendation.text = conciseRecommendation
        binding.analysisProgress.isVisible = false
        binding.analysisBanner.text = bannerLabelFor(result.riskLevel)
        binding.analysisLastScan.isVisible = true
        binding.analysisLastScan.text = getString(
            com.gemmaguard.app.R.string.analysis_last_scan,
            formatScanTimestamp(timestampMillis),
        )
        binding.analysisWarningIcon.isVisible = isHighRisk
        binding.analysisSourceBadge.isVisible = false
        binding.analysisSourceBadge.text = sourceLabelFor(source)
        binding.analysisConfidence.text = formatConfidenceValue(result.confidence)
        binding.analysisConfidenceRow.isVisible = false
        binding.analysisStatus.text = summaryLine
        binding.analysisReasonsTitle.isVisible = false
        binding.analysisReasons.isVisible = false
        binding.analysisReasons.text = detailReasons.joinToString(separator = "\n") { "• $it" }
        binding.analysisRecommendationTitle.isVisible = false
        binding.analysisRecommendation.isVisible = false
        binding.analysisRecommendation.text = conciseRecommendation
        binding.analysisModelSummary.text = buildString {
            append(sourceLabelFor(source))
            append("\n")
            append(modelDetailsFor(source))
        }
        binding.analysisDetailsConfidence.text = getString(
            com.gemmaguard.app.R.string.analysis_details_confidence,
            formatConfidenceValue(result.confidence),
        )
        binding.analysisDetailsReasonsTitle.isVisible = detailReasons.isNotEmpty()
        binding.analysisDetailsReasons.isVisible = detailReasons.isNotEmpty()
        binding.analysisDetailsReasons.text = detailReasons.joinToString(separator = "\n") { "• $it" }
        binding.analysisDetailsRecommendationTitle.isVisible = true
        binding.analysisDetailsRecommendation.isVisible = true
        binding.analysisDetailsRecommendation.text = conciseRecommendation
        binding.analysisMoreInfoButton.isVisible = true
        isAdvancedInfoExpanded = false
        binding.analysisShareButton.isVisible = true
        renderMoreInfoSection()
    }

    private fun renderAnalysisLoading() {
        applyAnalysisCardColors(riskPaletteFor(RiskLevel.UNKNOWN))
        setHighRiskWarningVisible(false)
        currentAnalysisEntry = null
        binding.analysisCard.isVisible = false
        binding.scanningOverlay.isVisible = true
        binding.overlayScanningSection.isVisible = true
        binding.overlayResultBanner.isVisible = false
        binding.overlayResultSection.isVisible = false
        binding.overlayWarningIcon.isVisible = false
        binding.overlayConfidenceRow.isVisible = true
        binding.analysisProgress.isVisible = true
        binding.analysisBanner.text = getString(com.gemmaguard.app.R.string.analysis_banner_loading)
        binding.analysisLastScan.isVisible = false
        binding.analysisSourceBadge.isVisible = true
        binding.analysisSourceBadge.text = getString(com.gemmaguard.app.R.string.analysis_source_gemma)
        binding.analysisConfidence.text = ""
        binding.analysisConfidenceRow.isVisible = false
        binding.analysisWarningIcon.isVisible = false
        binding.analysisStatus.text = getString(com.gemmaguard.app.R.string.analysis_status_loading_static)
        binding.analysisMoreInfoButton.isVisible = false
        binding.analysisShareButton.isVisible = false
        binding.analysisMoreInfoSection.isVisible = false
        isAdvancedInfoExpanded = false
        binding.analysisReasonsTitle.isVisible = false
        binding.analysisReasons.isVisible = false
        binding.analysisRecommendationTitle.isVisible = false
        binding.analysisRecommendation.isVisible = false
        clearAnalysisDetailFields()
        renderAnalysisDiagnostics(
            title = null,
            details = null,
        )
        startOverlayMessageCycle()
    }

    private fun renderAnalysisEmpty(status: String) {
        stopOverlayMessageCycle()
        currentAnalysisEntry = null
        setHighRiskWarningVisible(false)
        binding.scanningOverlay.isVisible = false
        binding.analysisCard.isVisible = false
        binding.analysisLastScan.isVisible = false
        binding.analysisStatus.text = status
    }

    private fun renderAnalysisFailure(userStatus: String, diagnostics: String) {
        applyAnalysisCardColors(riskPaletteFor(RiskLevel.UNKNOWN))
        stopOverlayMessageCycle()
        currentAnalysisEntry = null
        setHighRiskWarningVisible(false)
        binding.scanningOverlay.isVisible = false
        binding.analysisCard.isVisible = true
        binding.analysisProgress.isVisible = false
        binding.analysisBanner.text = getString(com.gemmaguard.app.R.string.analysis_banner_idle)
        binding.analysisLastScan.isVisible = false
        binding.analysisWarningIcon.isVisible = false
        binding.analysisSourceBadge.isVisible = false
        binding.analysisConfidence.text = getString(com.gemmaguard.app.R.string.analysis_confidence_unavailable)
        binding.analysisConfidenceRow.isVisible = false
        binding.analysisStatus.text = userStatus
        binding.analysisReasonsTitle.isVisible = false
        binding.analysisReasons.isVisible = false
        binding.analysisRecommendationTitle.isVisible = false
        binding.analysisRecommendation.isVisible = false
        clearAnalysisDetailFields()
        binding.analysisMoreInfoButton.isVisible = true
        isAdvancedInfoExpanded = false
        binding.analysisShareButton.isVisible = false
        binding.analysisModelSummary.text = gemmaAnalyzer.engineSummary()
        renderMoreInfoSection()
        renderAnalysisDiagnostics(
            title = getString(com.gemmaguard.app.R.string.analysis_diagnostics_title),
            details = diagnostics,
        )
    }

    private fun setHighRiskWarningVisible(isVisible: Boolean) {
        binding.highRiskWarningFrame.isVisible = false
        binding.overlayHighRiskWarningFrame.isVisible = isVisible
    }

    private fun clearAnalysisDetailFields() {
        binding.analysisDetailsConfidence.text = ""
        binding.analysisPrivacySummary.text = getString(com.gemmaguard.app.R.string.analysis_privacy_summary)
        binding.analysisDetailsReasonsTitle.isVisible = false
        binding.analysisDetailsReasons.isVisible = false
        binding.analysisDetailsReasons.text = ""
        binding.analysisDetailsRecommendationTitle.isVisible = false
        binding.analysisDetailsRecommendation.isVisible = false
        binding.analysisDetailsRecommendation.text = ""
    }

    private fun modelDetailsFor(source: AnalysisSource): String {
        return if (source == AnalysisSource.GEMMA4) {
            getString(com.gemmaguard.app.R.string.analysis_model_details_gemma)
        } else {
            gemmaAnalyzer.engineSummary()
        }
    }

    private fun startAnalysisTicker() {
        analysisTickerJob?.cancel()
        analysisTickerJob = lifecycleScope.launch {
            var elapsedSeconds = 0L
            while (elapsedSeconds < ANALYSIS_TIMEOUT_SECONDS && isActive) {
                delay(1_000)
                elapsedSeconds += 1
            }
        }
    }

    private fun stopAnalysisTicker() {
        analysisTickerJob?.cancel()
        analysisTickerJob = null
    }

    private fun renderAnalysisDiagnostics(title: String?, details: String?) {
        binding.analysisDiagnosticsTitle.isVisible = !title.isNullOrBlank()
        binding.analysisDiagnostics.isVisible = !details.isNullOrBlank()
        binding.analysisDiagnosticsTitle.text = title.orEmpty()
        binding.analysisDiagnostics.text = details.orEmpty()
    }

    private fun formatScanTimestamp(timestampMillis: Long): String {
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
        ).format(timestampMillis)
    }

    private fun appendHistoryEntry(
        screenshot: Bitmap?,
        ocrText: String,
        result: PhishingAnalysisResult,
        source: AnalysisSource,
        timestampMillis: Long,
    ) {
        scanHistory.addFirst(
            ScanHistoryEntry(
                timestampMillis = timestampMillis,
                source = source,
                result = result,
                summary = sanitizeDisplayText(result.reasons.firstOrNull() ?: result.recommendation),
                ocrPreview = ocrText
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.take(96)
                    .orEmpty(),
                screenshotPath = persistHistoryScreenshot(screenshot),
            ),
        )
        currentAnalysisEntry = scanHistory.firstOrNull()
        while (scanHistory.size > HISTORY_LIMIT) {
            scanHistory.removeLast().deleteAssets()
        }
        renderHistory()
    }

    private fun renderHistory() {
        binding.historyCard.isVisible = scanHistory.isNotEmpty()
        binding.historyContainer.removeAllViews()

        scanHistory.forEach { entry ->
            val itemBinding = ItemScanHistoryBinding.inflate(layoutInflater, binding.historyContainer, false)
            val palette = riskPaletteFor(entry.result.riskLevel)
            itemBinding.historyTimestamp.text = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
            ).format(entry.timestampMillis)
            itemBinding.historySource.text = sourceLabelFor(entry.source)
            itemBinding.historyRiskBadge.text = riskLabelFor(entry.result.riskLevel)
            itemBinding.historyConfidence.text = getString(
                com.gemmaguard.app.R.string.history_confidence,
                formatConfidenceValue(entry.result.confidence),
            )
            itemBinding.historySummary.text = entry.summary
            itemBinding.historyPreview.isVisible = entry.ocrPreview.isNotBlank()
            itemBinding.historyPreview.text = entry.ocrPreview
            itemBinding.historyShare.setOnClickListener {
                shareHistoryEntry(entry)
            }

            itemBinding.historyRiskBadge.setTextColor(getColor(palette.accentColorRes))
            itemBinding.historyRiskBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = resources.displayMetrics.density * 999
                setColor(getColor(com.gemmaguard.app.R.color.panel))
                setStroke((resources.displayMetrics.density * 1).toInt(), getColor(palette.accentColorRes))
            }
            itemBinding.historySource.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = resources.displayMetrics.density * 999
                setColor(getColor(com.gemmaguard.app.R.color.accentSurface))
            }
            binding.historyContainer.addView(itemBinding.root)
        }
    }

    private fun shareHistoryEntry(entry: ScanHistoryEntry) {
        showSharePreview(entry)
    }

    private fun shareCurrentAnalysis() {
        currentAnalysisEntry?.let(::shareHistoryEntry)
    }

    private fun buildShareText(entry: ScanHistoryEntry): String {
        return listOf(
            shareTitleFor(entry.result.riskLevel),
            "",
            shareBodyFor(entry.result.riskLevel),
            "",
            getString(
                com.gemmaguard.app.R.string.history_confidence,
                formatConfidenceValue(entry.result.confidence),
            ),
            "",
            getString(com.gemmaguard.app.R.string.share_summary_recommendation_title),
            conciseRecommendationFor(entry.result),
            "",
            getString(com.gemmaguard.app.R.string.share_summary_footer),
        ).joinToString(separator = "\n")
    }

    private fun shareTitleFor(riskLevel: RiskLevel): String {
        val titleRes = when (riskLevel) {
            RiskLevel.HIGH -> com.gemmaguard.app.R.string.share_summary_high_title
            RiskLevel.MEDIUM -> com.gemmaguard.app.R.string.share_summary_medium_title
            RiskLevel.LOW -> com.gemmaguard.app.R.string.share_summary_low_title
            RiskLevel.UNKNOWN -> com.gemmaguard.app.R.string.share_summary_unknown_title
        }
        return getString(titleRes)
    }

    private fun shareBodyFor(riskLevel: RiskLevel): String {
        val bodyRes = when (riskLevel) {
            RiskLevel.HIGH -> com.gemmaguard.app.R.string.share_summary_high_body
            RiskLevel.MEDIUM -> com.gemmaguard.app.R.string.share_summary_medium_body
            RiskLevel.LOW -> com.gemmaguard.app.R.string.share_summary_low_body
            RiskLevel.UNKNOWN -> com.gemmaguard.app.R.string.share_summary_unknown_body
        }
        return getString(bodyRes)
    }

    private fun conciseRecommendationFor(result: PhishingAnalysisResult): String {
        val recommendation = sanitizeDisplayText(result.recommendation)
        if (
            result.riskLevel != RiskLevel.HIGH &&
            recommendation.length <= MAX_SHARE_RECOMMENDATION_LENGTH &&
            !recommendation.contains(";") &&
            !recommendation.contains(". ")
        ) {
            return recommendation
        }

        val recommendationRes = when (result.riskLevel) {
            RiskLevel.HIGH -> com.gemmaguard.app.R.string.share_default_high_recommendation
            RiskLevel.MEDIUM -> com.gemmaguard.app.R.string.share_default_medium_recommendation
            RiskLevel.LOW -> com.gemmaguard.app.R.string.share_default_low_recommendation
            RiskLevel.UNKNOWN -> com.gemmaguard.app.R.string.share_default_unknown_recommendation
        }
        return getString(recommendationRes)
    }

    private fun showSharePreview(entry: ScanHistoryEntry) {
        val previewBinding = DialogSharePreviewBinding.inflate(layoutInflater)
        val shareText = buildShareText(entry)
        val screenshotBitmap = decodeShareScreenshot(entry.screenshotPath)

        previewBinding.sharePreviewText.text = shareText
        previewBinding.sharePreviewScreenshotTitle.isVisible = screenshotBitmap != null
        previewBinding.sharePreviewScreenshotCard.isVisible = screenshotBitmap != null
        previewBinding.sharePreviewScreenshot.setImageBitmap(screenshotBitmap)

        MaterialAlertDialogBuilder(this)
            .setTitle(com.gemmaguard.app.R.string.share_preview_title)
            .setView(previewBinding.root)
            .setPositiveButton(com.gemmaguard.app.R.string.share_preview_action) { _, _ ->
                Toast.makeText(
                    this,
                    com.gemmaguard.app.R.string.share_ready_to_share,
                    Toast.LENGTH_SHORT,
                ).show()
                launchShareIntent(entry, shareText)
            }
            .setNegativeButton(com.gemmaguard.app.R.string.share_preview_cancel, null)
            .create()
            .apply {
                setOnDismissListener {
                    previewBinding.sharePreviewScreenshot.setImageDrawable(null)
                    if (screenshotBitmap != null && !screenshotBitmap.isRecycled) {
                        screenshotBitmap.recycle()
                    }
                }
            }
            .show()
    }

    private fun launchShareIntent(entry: ScanHistoryEntry, shareText: String) {
        val screenshotUri = screenshotUriFor(entry.screenshotPath)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (screenshotUri != null) "image/*" else "text/plain"
            putExtra(
                Intent.EXTRA_SUBJECT,
                getString(
                    com.gemmaguard.app.R.string.history_share_subject,
                    riskLabelFor(entry.result.riskLevel),
                ),
            )
            putExtra(Intent.EXTRA_TEXT, shareText)
            screenshotUri?.let { uri ->
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "Gemma Guard screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(com.gemmaguard.app.R.string.history_share_chooser),
            ),
        )
    }

    private fun screenshotUriFor(path: String?): Uri? {
        if (path.isNullOrBlank()) {
            return null
        }

        val screenshotFile = File(path)
        if (!screenshotFile.isFile) {
            return null
        }

        return FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            screenshotFile,
        )
    }

    private fun decodeShareScreenshot(path: String?): Bitmap? {
        if (path.isNullOrBlank()) {
            return null
        }

        val screenshotFile = File(path)
        if (!screenshotFile.isFile) {
            return null
        }

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(screenshotFile.absolutePath, boundsOptions)
        val sampleSize = calculateInSampleSize(
            width = boundsOptions.outWidth,
            height = boundsOptions.outHeight,
            reqWidth = 1080,
            reqHeight = 720,
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeFile(screenshotFile.absolutePath, decodeOptions)
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw IllegalArgumentException("Selected image could not be read.")

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = boundsOptions.outWidth,
                height = boundsOptions.outHeight,
                reqWidth = MAX_UPLOADED_IMAGE_DIMENSION,
                reqHeight = MAX_UPLOADED_IMAGE_DIMENSION,
            )
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: throw IllegalArgumentException("Selected image could not be decoded.")
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun persistHistoryScreenshot(bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled) {
            return null
        }

        val outputFile = File(cacheDir, "history-scan-${System.currentTimeMillis()}.jpg")
        return runCatching {
            outputFile.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
            outputFile.absolutePath
        }.getOrNull()
    }

    private fun buildDiagnosticsMessage(prefix: String, throwable: Throwable): String {
        val message = throwable.message?.trim().orEmpty().ifEmpty { "No exception message was provided." }
        return "$prefix\n${throwable::class.java.simpleName}: $message"
    }

    private fun replaceLastRecognizedBitmap(bitmap: Bitmap?) {
        val previousBitmap = lastRecognizedBitmap
        lastRecognizedBitmap = bitmap
        if (previousBitmap != null && previousBitmap !== bitmap && !previousBitmap.isRecycled) {
            previousBitmap.recycle()
        }
    }

    private fun releaseLastRecognizedBitmap() {
        replaceLastRecognizedBitmap(null)
    }

    private fun modelPreparationFailureMessage(throwable: Throwable): String {
        val message = throwable.message?.trim().orEmpty()
        return if (message.contains("No staged Gemma 4 model was found", ignoreCase = true) ||
            message.contains("Gemma 4 model file not found", ignoreCase = true)
        ) {
            getString(com.gemmaguard.app.R.string.model_preparation_status_missing_model)
        } else {
            getString(
                com.gemmaguard.app.R.string.model_preparation_status_failed,
                message.ifEmpty { "Unknown error" },
            )
        }
    }

    private fun toggleMoreInfo() {
        isMoreInfoExpanded = !isMoreInfoExpanded
        renderMoreInfoSection()
    }

    private fun toggleAdvancedInfo() {
        isAdvancedInfoExpanded = !isAdvancedInfoExpanded
        renderAdvancedInfoSection()
    }

    private fun renderMoreInfoSection() {
        binding.analysisMoreInfoSection.isVisible = isMoreInfoExpanded && binding.analysisMoreInfoButton.isVisible
        binding.analysisMoreInfoButton.text = getString(
            if (isMoreInfoExpanded) com.gemmaguard.app.R.string.analysis_hide_information
            else com.gemmaguard.app.R.string.analysis_more_information,
        )
        renderAdvancedInfoSection()
    }

    private fun renderAdvancedInfoSection() {
        val canShowAdvanced = isMoreInfoExpanded && binding.analysisMoreInfoButton.isVisible
        binding.analysisAdvancedSection.isVisible = canShowAdvanced && isAdvancedInfoExpanded
        binding.analysisTechnicalToggleButton.isVisible = canShowAdvanced
        binding.analysisTechnicalToggleButton.text = getString(
            if (isAdvancedInfoExpanded) com.gemmaguard.app.R.string.analysis_hide_technical_details
            else com.gemmaguard.app.R.string.analysis_show_technical_details,
        )
    }

    private fun startOverlayMessageCycle() {
        overlayMessageJob?.cancel()
        overlayMessageJob = lifecycleScope.launch {
            val phrases = listOf(
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_analyzing),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_suspicious),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_links),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_intent),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_urgency),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_payment),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_brand),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_layout),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_credentials),
                getString(com.gemmaguard.app.R.string.analysis_overlay_phrase_private),
            )
            var index = 0
            while (isActive) {
                animateOverlayPhrase(phrases[index % phrases.size])
                index += 1
                delay(650)
            }
        }
    }

    private suspend fun animateOverlayPhrase(phrase: String) {
        val builder = StringBuilder()
        for (character in phrase) {
            if (overlayMessageJob?.isActive != true) return
            builder.append(character)
            binding.scanningOverlayStatus.text = builder.toString()
            delay(28)
        }
        delay(950)
    }

    private fun stopOverlayMessageCycle() {
        overlayMessageJob?.cancel()
        overlayMessageJob = null
    }

    private fun formatConfidenceValue(confidence: Int): String {
        return if (confidence in 1..100) {
            getString(com.gemmaguard.app.R.string.analysis_confidence_value, confidence)
        } else {
            getString(com.gemmaguard.app.R.string.analysis_confidence_unavailable)
        }
    }

    private fun sanitizeDisplayText(text: String): String {
        return text
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isDuplicateSummaryReason(reason: String, summary: String): Boolean {
        val normalizedReason = normalizeForComparison(reason)
        val normalizedSummary = normalizeForComparison(summary)
        if (normalizedReason.isBlank() || normalizedSummary.isBlank()) {
            return false
        }

        return normalizedReason == normalizedSummary ||
            normalizedReason.contains(normalizedSummary) ||
            normalizedSummary.contains(normalizedReason)
    }

    private fun normalizeForComparison(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanOcrTextForDisplay(text: String): String {
        val cleanedLines = text
            .lineSequence()
            .map { line ->
                line
                    .replace(Regex("[\\u0000-\\u001F]+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .filterNot(::isLikelySystemOcrNoise)
            .fold(mutableListOf<String>()) { lines, line ->
                if (lines.lastOrNull() != line) {
                    lines += line
                }
                lines
            }

        return cleanedLines
            .joinToString(separator = "\n")
            .ifBlank { sanitizeDisplayText(text) }
    }

    private fun isLikelySystemOcrNoise(line: String): Boolean {
        val normalized = line
            .trim(' ', '|', '•', '-', '_', '.', ',', ':')
            .trim()
        val lowercase = normalized.lowercase()

        if (lowercase.contains(Regex("(http|www\\.|@|account|verify|login|password|payment|paypal|bank|security|suspend|locked|urgent|action|access|data|device|money|transfer|code)"))) {
            return false
        }

        return normalized.length <= 2 ||
            normalized.matches(Regex("\\d{1,2}:\\d{2}\\s*([ap]m)?", RegexOption.IGNORE_CASE)) ||
            normalized.matches(Regex("(yesterday|today|tomorrow)(\\s*[•-]?\\s*\\d{1,2}:\\d{2}\\s*([ap]m)?)?", RegexOption.IGNORE_CASE)) ||
            normalized.matches(Regex("\\d{1,3}%")) ||
            normalized.matches(Regex("[\\d\\s()+-]{7,}")) ||
            normalized.matches(Regex("(lte|5g|4g|wifi|wi-fi|sms|mms|abc|english \\([a-z]+\\))", RegexOption.IGNORE_CASE)) ||
            normalized.matches(Regex("[^\\p{L}\\p{N}]{1,8}"))
    }

    private fun bannerLabelFor(riskLevel: RiskLevel): String {
        val labelRes = when (riskLevel) {
            RiskLevel.LOW -> com.gemmaguard.app.R.string.analysis_banner_low
            RiskLevel.MEDIUM -> com.gemmaguard.app.R.string.analysis_banner_medium
            RiskLevel.HIGH -> com.gemmaguard.app.R.string.analysis_banner_high
            RiskLevel.UNKNOWN -> com.gemmaguard.app.R.string.analysis_banner_idle
        }
        return getString(labelRes)
    }

    private fun sourceLabelFor(source: AnalysisSource): String {
        val labelRes = when (source) {
            AnalysisSource.GEMMA4 -> com.gemmaguard.app.R.string.analysis_source_gemma
            AnalysisSource.LOCAL_FALLBACK -> com.gemmaguard.app.R.string.analysis_source_fallback
        }
        return getString(labelRes)
    }

    private fun riskLabelFor(riskLevel: RiskLevel): String {
        val labelRes = when (riskLevel) {
            RiskLevel.LOW -> com.gemmaguard.app.R.string.analysis_risk_low
            RiskLevel.MEDIUM -> com.gemmaguard.app.R.string.analysis_risk_medium
            RiskLevel.HIGH -> com.gemmaguard.app.R.string.analysis_risk_high
            RiskLevel.UNKNOWN -> com.gemmaguard.app.R.string.analysis_risk_unknown
        }
        return getString(labelRes)
    }

    private fun applyAnalysisCardColors(palette: RiskPalette) {
        val strokeColor = getColor(palette.accentColorRes)
        binding.analysisCard.setCardBackgroundColor(getColor(palette.surfaceColorRes))
        binding.analysisCard.strokeColor = strokeColor
        binding.analysisBanner.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(strokeColor)
        }
        binding.analysisConfidenceContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(com.gemmaguard.app.R.color.panel))
            setStroke((resources.displayMetrics.density * 5).toInt(), getColor(com.gemmaguard.app.R.color.accent))
        }
        binding.analysisSourceBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 999
            setColor(getColor(com.gemmaguard.app.R.color.accentSurface))
        }
        binding.analysisSourceBadge.setTextColor(getColor(com.gemmaguard.app.R.color.accentDark))
    }

    private fun riskPaletteFor(riskLevel: RiskLevel): RiskPalette {
        return when (riskLevel) {
            RiskLevel.LOW -> RiskPalette(
                accentColorRes = com.gemmaguard.app.R.color.riskLow,
                surfaceColorRes = com.gemmaguard.app.R.color.riskLowSurface,
            )
            RiskLevel.MEDIUM -> RiskPalette(
                accentColorRes = com.gemmaguard.app.R.color.riskMedium,
                surfaceColorRes = com.gemmaguard.app.R.color.riskMediumSurface,
            )
            RiskLevel.HIGH -> RiskPalette(
                accentColorRes = com.gemmaguard.app.R.color.riskHigh,
                surfaceColorRes = com.gemmaguard.app.R.color.riskHighSurface,
            )
            RiskLevel.UNKNOWN -> RiskPalette(
                accentColorRes = com.gemmaguard.app.R.color.riskUnknown,
                surfaceColorRes = com.gemmaguard.app.R.color.riskUnknownSurface,
            )
        }
    }

    private data class RiskPalette(
        @param:ColorRes val accentColorRes: Int,
        @param:ColorRes val surfaceColorRes: Int,
    )

    companion object {
        private const val ANALYSIS_TIMEOUT_SECONDS = 60
        private const val ANALYSIS_TIMEOUT_MS = ANALYSIS_TIMEOUT_SECONDS * 1_000L
        private const val HISTORY_LIMIT = 10
        private const val MAX_UPLOADED_IMAGE_DIMENSION = 1600
        private const val MAX_SHARE_RECOMMENDATION_LENGTH = 90
        private const val TAG = "GemmaGuard"
    }

    private class AnalysisTimeoutException(message: String) : RuntimeException(message)

    private enum class AnalysisSource {
        GEMMA4,
        LOCAL_FALLBACK,
    }

    private data class ScanHistoryEntry(
        val timestampMillis: Long,
        val source: AnalysisSource,
        val result: PhishingAnalysisResult,
        val summary: String,
        val ocrPreview: String,
        val screenshotPath: String?,
    ) {
        fun deleteAssets() {
            screenshotPath?.let { path ->
                runCatching {
                    File(path).delete()
                }
            }
        }
    }
}
