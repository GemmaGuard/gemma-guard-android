package com.gemmaguard.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
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
import com.gemmaguard.app.databinding.ItemScanHistoryBinding
import com.gemmaguard.app.databinding.ShareReportImageBinding
import com.gemmaguard.app.model.PhishingAnalysisResult
import com.gemmaguard.app.model.RiskLevel
import com.gemmaguard.app.ocr.OcrTextFormatter
import com.gemmaguard.app.ocr.TextRecognitionEngine
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
        binding.analysisShareButton.setOnClickListener { shareCurrentAnalysis() }
        binding.overlayDismissButton.setOnClickListener {
            binding.scanningOverlay.isVisible = false
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
            renderAnalysisResult(
                result = result,
                source = AnalysisSource.GEMMA4,
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
            )
        }.onFailure { throwable ->
            Log.e(TAG, "Gemma 4 analysis failed. Falling back to local analyzer.", throwable)
            val fallbackResult = localPhishingAnalyzer.analyze(input)
            renderAnalysisResult(
                result = fallbackResult,
                source = AnalysisSource.LOCAL_FALLBACK,
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
        binding.analysisOcrText.text = text
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
    ) {
        val riskColors = riskPaletteFor(result.riskLevel)
        val displayReasons = result.reasons.map(::sanitizeDisplayText)
        val displaySummary = displayReasons.firstOrNull()
            ?: sanitizeDisplayText(result.recommendation)
        applyAnalysisCardColors(riskColors)
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
        binding.overlayResultConfidence.text = formatConfidenceValue(result.confidence)
        binding.overlayResultConfidenceContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(com.gemmaguard.app.R.color.panel))
            setStroke((resources.displayMetrics.density * 5).toInt(), getColor(com.gemmaguard.app.R.color.accentSurface))
        }
        binding.overlayResultSource.text = sourceLabelFor(source)
        binding.overlayResultSummary.text = displaySummary
        binding.overlayResultReasons.text = displayReasons.take(3).joinToString("\n") { "• $it" }
        binding.overlayResultRecommendation.text = sanitizeDisplayText(result.recommendation)
        binding.analysisProgress.isVisible = false
        binding.analysisBanner.text = bannerLabelFor(result.riskLevel)
        binding.analysisSourceBadge.isVisible = true
        binding.analysisSourceBadge.text = sourceLabelFor(source)
        binding.analysisConfidence.text = formatConfidenceValue(result.confidence)
        binding.analysisStatus.text = displaySummary
        binding.analysisReasonsTitle.isVisible = displayReasons.isNotEmpty()
        binding.analysisReasons.isVisible = displayReasons.isNotEmpty()
        binding.analysisReasons.text = displayReasons.joinToString(separator = "\n") { "• $it" }
        binding.analysisRecommendationTitle.isVisible = true
        binding.analysisRecommendation.isVisible = true
        binding.analysisRecommendation.text = sanitizeDisplayText(result.recommendation)
        binding.analysisModelSummary.text = buildString {
            append(sourceLabelFor(source))
            append("\n")
            append(gemmaAnalyzer.engineSummary())
        }
        binding.analysisMoreInfoButton.isVisible = true
        binding.analysisShareButton.isVisible = true
        renderMoreInfoSection()
    }

    private fun renderAnalysisLoading() {
        applyAnalysisCardColors(riskPaletteFor(RiskLevel.UNKNOWN))
        currentAnalysisEntry = null
        binding.analysisCard.isVisible = false
        binding.scanningOverlay.isVisible = true
        binding.overlayScanningSection.isVisible = true
        binding.overlayResultBanner.isVisible = false
        binding.overlayResultSection.isVisible = false
        binding.analysisProgress.isVisible = true
        binding.analysisBanner.text = getString(com.gemmaguard.app.R.string.analysis_banner_loading)
        binding.analysisSourceBadge.isVisible = true
        binding.analysisSourceBadge.text = getString(com.gemmaguard.app.R.string.analysis_source_gemma)
        binding.analysisConfidence.text = ""
        binding.analysisStatus.text = getString(com.gemmaguard.app.R.string.analysis_status_loading_static)
        binding.analysisMoreInfoButton.isVisible = false
        binding.analysisShareButton.isVisible = false
        binding.analysisMoreInfoSection.isVisible = false
        binding.analysisReasonsTitle.isVisible = false
        binding.analysisReasons.isVisible = false
        binding.analysisRecommendationTitle.isVisible = false
        binding.analysisRecommendation.isVisible = false
        renderAnalysisDiagnostics(
            title = null,
            details = null,
        )
        startOverlayMessageCycle()
    }

    private fun renderAnalysisEmpty(status: String) {
        stopOverlayMessageCycle()
        currentAnalysisEntry = null
        binding.scanningOverlay.isVisible = false
        binding.analysisCard.isVisible = false
        binding.analysisStatus.text = status
    }

    private fun renderAnalysisFailure(userStatus: String, diagnostics: String) {
        applyAnalysisCardColors(riskPaletteFor(RiskLevel.UNKNOWN))
        stopOverlayMessageCycle()
        currentAnalysisEntry = null
        binding.scanningOverlay.isVisible = false
        binding.analysisCard.isVisible = true
        binding.analysisProgress.isVisible = false
        binding.analysisBanner.text = getString(com.gemmaguard.app.R.string.analysis_banner_idle)
        binding.analysisSourceBadge.isVisible = false
        binding.analysisConfidence.text = getString(com.gemmaguard.app.R.string.analysis_confidence_unavailable)
        binding.analysisStatus.text = userStatus
        binding.analysisReasonsTitle.isVisible = false
        binding.analysisReasons.isVisible = false
        binding.analysisRecommendationTitle.isVisible = false
        binding.analysisRecommendation.isVisible = false
        binding.analysisMoreInfoButton.isVisible = true
        binding.analysisShareButton.isVisible = false
        binding.analysisModelSummary.text = gemmaAnalyzer.engineSummary()
        renderMoreInfoSection()
        renderAnalysisDiagnostics(
            title = getString(com.gemmaguard.app.R.string.analysis_diagnostics_title),
            details = diagnostics,
        )
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

    private fun appendHistoryEntry(
        screenshot: Bitmap?,
        ocrText: String,
        result: PhishingAnalysisResult,
        source: AnalysisSource,
    ) {
        scanHistory.addFirst(
            ScanHistoryEntry(
                timestampMillis = System.currentTimeMillis(),
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
        val imageUri = createReportImageUri(entry)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(
                Intent.EXTRA_SUBJECT,
                getString(
                    com.gemmaguard.app.R.string.history_share_subject,
                    riskLabelFor(entry.result.riskLevel),
                ),
            )
            putExtra(Intent.EXTRA_TEXT, buildShareText(entry))
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(com.gemmaguard.app.R.string.history_share_chooser),
            ),
        )
    }

    private fun shareCurrentAnalysis() {
        currentAnalysisEntry?.let(::shareHistoryEntry)
    }

    private fun buildShareText(entry: ScanHistoryEntry): String {
        return getString(com.gemmaguard.app.R.string.share_saved_me)
    }

    private fun createReportImageUri(entry: ScanHistoryEntry): Uri {
        val binding = ShareReportImageBinding.inflate(LayoutInflater.from(this))
        val palette = riskPaletteFor(entry.result.riskLevel)
        val cleanedReasons = entry.result.reasons.map(::sanitizeDisplayText)
        val cleanedSummary = sanitizeDisplayText(entry.summary)

        binding.shareSource.text = sourceLabelFor(entry.source)
        binding.shareBanner.text = bannerLabelFor(entry.result.riskLevel)
        binding.shareSummary.text = cleanedSummary
        binding.shareConfidence.text = formatConfidenceValue(entry.result.confidence)
        binding.shareReasons.text = cleanedReasons.take(2).joinToString(separator = "\n") { "• $it" }
        binding.shareRecommendation.text = getString(
            com.gemmaguard.app.R.string.share_recommendation_inline,
            sanitizeDisplayText(entry.result.recommendation),
        )
        val screenshotBitmap = decodeShareScreenshot(entry.screenshotPath)
        binding.shareScreenshot.setImageBitmap(screenshotBitmap)
        binding.shareScreenshot.isVisible = screenshotBitmap != null
        binding.shareScreenshotTitle.isVisible = screenshotBitmap != null

        binding.shareBanner.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(getColor(palette.accentColorRes))
        }
        binding.shareConfidenceContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(com.gemmaguard.app.R.color.panel))
            setStroke((resources.displayMetrics.density * 5).toInt(), getColor(com.gemmaguard.app.R.color.accent))
        }

        val bitmap = createBitmapFromView(binding.root)
        return try {
            val outputFile = File(cacheDir, "shared-analysis-${entry.timestampMillis}.png")
            outputFile.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                outputFile,
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            if (screenshotBitmap != null && !screenshotBitmap.isRecycled) {
                screenshotBitmap.recycle()
            }
        }
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

    private fun createBitmapFromView(view: View): Bitmap {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            view.draw(canvas)
        }
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

    private fun renderMoreInfoSection() {
        binding.analysisMoreInfoSection.isVisible = isMoreInfoExpanded && binding.analysisMoreInfoButton.isVisible
        binding.analysisMoreInfoButton.text = getString(
            if (isMoreInfoExpanded) com.gemmaguard.app.R.string.analysis_hide_information
            else com.gemmaguard.app.R.string.analysis_more_information,
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
        binding.analysisCard.setCardBackgroundColor(getColor(com.gemmaguard.app.R.color.panel))
        binding.analysisCard.strokeColor = getColor(com.gemmaguard.app.R.color.panelStroke)
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
