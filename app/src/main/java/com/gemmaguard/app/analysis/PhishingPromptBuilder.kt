package com.gemmaguard.app.analysis

import android.content.Context
import java.io.FileNotFoundException

class PhishingPromptBuilder(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val preferredLanguageCode: String by lazy {
        appContext.resources.configuration.locales[0]?.language.orEmpty().lowercase()
    }
    private val systemPromptTemplate: String by lazy {
        loadLocalizedPromptAsset(SYSTEM_PROMPT_FILE_NAME)
    }
    private val userPromptTemplate: String by lazy {
        loadLocalizedPromptAsset(USER_PROMPT_FILE_NAME)
    }

    fun buildBundle(ocrText: String): PromptBundle {
        val sanitizedText = ocrText.trim().ifEmpty {
            if (preferredLanguageCode == RUSSIAN_LANGUAGE_CODE) {
                "OCR-текст не был извлечен."
            } else {
                "No OCR text was extracted."
            }
        }

        return PromptBundle(
            systemInstruction = systemPromptTemplate,
            userPrompt = userPromptTemplate.replace(OCR_TEXT_PLACEHOLDER, sanitizedText),
        )
    }

    private fun loadLocalizedPromptAsset(fileName: String): String {
        val localizedPath = buildLocalizedPromptPath(fileName)
        val fallbackPath = "$PROMPTS_ROOT/$fileName"
        return runCatching {
            loadPromptAsset(localizedPath)
        }.recoverCatching { error ->
            if (error is FileNotFoundException || error.cause is FileNotFoundException) {
                loadPromptAsset(fallbackPath)
            } else {
                throw error
            }
        }.getOrThrow()
    }

    private fun buildLocalizedPromptPath(fileName: String): String {
        return if (preferredLanguageCode == RUSSIAN_LANGUAGE_CODE) {
            "$PROMPTS_ROOT/$RUSSIAN_LANGUAGE_CODE/$fileName"
        } else {
            "$PROMPTS_ROOT/$fileName"
        }
    }

    private fun loadPromptAsset(path: String): String {
        return assets.open(path)
            .bufferedReader()
            .use { reader -> reader.readText().trim() }
    }

    companion object {
        private const val PROMPTS_ROOT = "prompts"
        private const val RUSSIAN_LANGUAGE_CODE = "ru"
        private const val SYSTEM_PROMPT_FILE_NAME = "system_prompt.txt"
        private const val USER_PROMPT_FILE_NAME = "user_prompt.txt"
        private const val OCR_TEXT_PLACEHOLDER = "{{OCR_TEXT}}"
    }
}
