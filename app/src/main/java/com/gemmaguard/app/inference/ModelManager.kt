package com.gemmaguard.app.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ModelManager(
    private val context: Context,
    private val modelLocator: ModelLocator = ModelLocator(context),
) : AutoCloseable {
    private val engineLock = ReentrantLock()
    private var engine: Engine? = null
    private var initializedModelPath: String? = null
    @Volatile
    private var activeConversation: Conversation? = null

    fun availability(): ModelAvailability = modelLocator.availability()

    suspend fun prepareModelForUse(
        onProgress: suspend (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ModelAvailability = withContext(Dispatchers.IO) {
        val availability = modelLocator.availability()
        if (availability.activeLocation != null) {
            return@withContext availability
        }

        val stagedModel = availability.debugExternalTarget
        if (stagedModel != null && stagedModel.isFile && stagedModel.canRead()) {
            val destination = availability.appInternalTarget
            destination.parentFile?.mkdirs()
            val totalBytes = stagedModel.length().coerceAtLeast(1L)
            if (!destination.exists() || destination.length() != stagedModel.length()) {
                stagedModel.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copiedBytes = 0L
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) {
                                break
                            }
                            output.write(buffer, 0, bytesRead)
                            copiedBytes += bytesRead
                            onProgress(copiedBytes, totalBytes)
                        }
                        output.flush()
                    }
                }
            } else {
                onProgress(totalBytes, totalBytes)
            }
        }

        return@withContext modelLocator.availability()
    }

    suspend fun analyzeSingleTurn(
        systemInstruction: String,
        userContents: List<Content>,
    ): String = withContext(Dispatchers.IO) {
        analyzeSingleTurnBlocking(
            systemInstruction = systemInstruction,
            userContents = userContents,
        )
    }

    fun analyzeSingleTurnBlocking(
        systemInstruction: String,
        userContents: List<Content>,
    ): String {
        val liveEngine = ensureEngineInitialized()
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
        )

        val conversation = liveEngine.createConversation(conversationConfig)
        activeConversation = conversation
        return try {
            val response = conversation.sendMessage(Contents.of(*userContents.toTypedArray()))
            response.contents.contents
                .mapNotNull { content ->
                    (content as? Content.Text)?.text
                }
                .joinToString(separator = "\n")
                .trim()
        } finally {
            activeConversation = null
            conversation.close()
        }
    }

    fun cancelActiveConversation() {
        runCatching {
            activeConversation?.cancelProcess()
        }
    }

    private fun ensureEngineInitialized(): Engine {
        return engineLock.withLock {
            val availability = modelLocator.availability()
            val modelLocation = availability.activeLocation
                ?: throw FileNotFoundException(
                    buildString {
                        append("Gemma 4 model file not found. ")
                        append("Expected runtime model path: ")
                        append(availability.appInternalTarget.absolutePath)
                        availability.debugExternalTarget?.let {
                            append(". Debug staging path: ")
                            append(it.absolutePath)
                            append(". Push the model there first, then reopen Gemma Guard so it can copy the file into private app storage.")
                        }
                    },
                )

            val activeEngine = engine
            if (activeEngine != null && initializedModelPath == modelLocation.file.absolutePath) {
                activeEngine
            } else {
                activeEngine?.close()

                Engine(
                    EngineConfig(
                        modelPath = modelLocation.file.absolutePath,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                ).also { initialized ->
                    initialized.initialize()
                    engine = initialized
                    initializedModelPath = modelLocation.file.absolutePath
                }
            }
        }
    }

    override fun close() {
        cancelActiveConversation()
        engine?.close()
        engine = null
        initializedModelPath = null
    }
}
