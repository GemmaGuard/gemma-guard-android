package com.gemmaguard.app.inference

import android.content.Context
import com.gemmaguard.app.BuildConfig
import java.io.File

class ModelLocator(
    private val filesDir: File,
    private val modelFileName: String,
    private val debugModelPath: String?,
    private val allowDebugFallback: Boolean,
) {
    constructor(
        context: Context,
        modelFileName: String = BuildConfig.GEMMA_MODEL_FILE_NAME,
        debugModelPath: String = BuildConfig.GEMMA_DEBUG_MODEL_PATH,
        allowDebugFallback: Boolean = BuildConfig.DEBUG,
    ) : this(
        filesDir = context.filesDir,
        modelFileName = modelFileName,
        debugModelPath = debugModelPath,
        allowDebugFallback = allowDebugFallback,
    )

    fun availability(): ModelAvailability {
        val appInternalTarget = appInternalTarget()
        val debugExternalTarget = debugExternalTarget()

        val activeLocation = if (appInternalTarget.isFile && appInternalTarget.canRead()) {
            ModelLocation(
                file = appInternalTarget,
                source = ModelSource.APP_INTERNAL,
            )
        } else {
            null
        }

        return ModelAvailability(
            activeLocation = activeLocation,
            appInternalTarget = appInternalTarget,
            debugExternalTarget = debugExternalTarget,
        )
    }

    private fun appInternalTarget(): File {
        return File(File(filesDir, "models"), modelFileName)
    }

    private fun debugExternalTarget(): File? {
        if (!allowDebugFallback) {
            return null
        }

        val path = debugModelPath?.trim().orEmpty()
        if (path.isEmpty()) {
            return null
        }
        return File(path)
    }
}
