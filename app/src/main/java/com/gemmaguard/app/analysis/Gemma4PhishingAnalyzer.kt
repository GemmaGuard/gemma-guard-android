package com.gemmaguard.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.gemmaguard.app.inference.ModelAvailability
import com.gemmaguard.app.inference.ModelManager
import com.gemmaguard.app.inference.ModelSource
import com.gemmaguard.app.model.PhishingAnalysisResult
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class Gemma4PhishingAnalyzer(
    private val context: Context,
    private val promptBuilder: PhishingPromptBuilder = PhishingPromptBuilder(context.applicationContext),
    private val responseParser: Gemma4ResponseParser = Gemma4ResponseParser(),
    private val modelManager: ModelManager = ModelManager(context.applicationContext),
) : PhishingAnalyzer {
    override suspend fun analyze(input: PhishingAnalysisInput): PhishingAnalysisResult {
        return withContext(Dispatchers.IO) {
            analyzeBlocking(input)
        }
    }

    suspend fun prepareModelForUse(
        onProgress: suspend (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        modelManager.prepareModelForUse(onProgress)
    }

    fun analyzeBlocking(input: PhishingAnalysisInput): PhishingAnalysisResult {
        val bundle = promptBuilder.buildBundle(input.ocrText)
        val screenshot = requireNotNull(input.screenshot) {
            "Gemma 4 analysis requires a captured screenshot."
        }

        val screenshotFile = writeScreenshotToCache(screenshot)
        return try {
            val rawResponse = modelManager.analyzeSingleTurnBlocking(
                systemInstruction = bundle.systemInstruction,
                userContents = listOf(
                    Content.ImageFile(screenshotFile.absolutePath),
                    Content.Text(bundle.userPrompt),
                ),
            )
            responseParser.parse(rawResponse)
        } finally {
            deleteScreenshotFile(screenshotFile)
        }
    }

    fun cancelActiveAnalysis() {
        modelManager.cancelActiveConversation()
    }

    fun engineSummary(): String {
        val availability = modelManager.availability()
        return when (val activeLocation = availability.activeLocation) {
            null -> buildString {
                append("Gemma 4 is not ready yet. ")
                append("Expected app-managed model at ")
                append(availability.appInternalTarget.absolutePath)
                availability.debugExternalTarget?.let {
                    append(". Debug staging path: ")
                    append(it.absolutePath)
                    append(". The app copies this staged file into private storage during setup.")
                }
            }

            else -> buildString {
                append("Gemma 4 will load from ")
                append(activeLocation.file.absolutePath)
                append(". Source: ")
                append(
                    when (activeLocation.source) {
                        ModelSource.APP_INTERNAL -> "app-managed private storage"
                        ModelSource.DEBUG_EXTERNAL -> "debug staged model"
                    },
                )
                append(". Future release builds should fill this same location through onboarding download.")
            }
        }
    }

    fun modelAvailability(): ModelAvailability = modelManager.availability()

    fun close() {
        modelManager.close()
    }

    private fun writeScreenshotToCache(bitmap: Bitmap): File {
        return File.createTempFile("gemma-guard-capture-", ".png", context.cacheDir).apply {
            outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }

    private fun deleteScreenshotFile(file: File) {
        if (!file.exists()) {
            return
        }

        val deleted = runCatching {
            if (file.delete()) {
                true
            } else {
                file.writeBytes(ByteArray(0))
                file.delete()
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to delete temporary screenshot file.", error)
            false
        }

        if (!deleted) {
            Log.w(TAG, "Temporary screenshot file remained on disk at ${file.absolutePath}.")
        }
    }

    companion object {
        private const val TAG = "Gemma4Analyzer"
    }
}
