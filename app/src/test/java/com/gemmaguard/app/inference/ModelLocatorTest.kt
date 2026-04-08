package com.gemmaguard.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelLocatorTest {
    @Test
    fun availability_prefersAppInternalModel() {
        val filesDir = Files.createTempDirectory("gemma-guard-models").toFile()
        val internalModel = File(filesDir, "models/gemma-4-E2B-it.litertlm").apply {
            parentFile?.mkdirs()
            writeText("internal")
        }
        File(filesDir, "debug-model.litertlm").writeText("debug")

        val locator = ModelLocator(
            filesDir = filesDir,
            modelFileName = "gemma-4-E2B-it.litertlm",
            debugModelPath = File(filesDir, "debug-model.litertlm").absolutePath,
            allowDebugFallback = true,
        )

        val availability = locator.availability()

        assertEquals(internalModel.absolutePath, availability.activeLocation?.file?.absolutePath)
        assertEquals(ModelSource.APP_INTERNAL, availability.activeLocation?.source)
    }

    @Test
    fun availability_usesDebugFallbackWhenInternalModelIsMissing() {
        val filesDir = Files.createTempDirectory("gemma-guard-models").toFile()
        val debugModel = File(filesDir, "debug-model.litertlm").apply {
            writeText("debug")
        }

        val locator = ModelLocator(
            filesDir = filesDir,
            modelFileName = "gemma-4-E2B-it.litertlm",
            debugModelPath = debugModel.absolutePath,
            allowDebugFallback = true,
        )

        val availability = locator.availability()

        assertNull(availability.activeLocation)
        assertEquals(debugModel.absolutePath, availability.debugExternalTarget?.absolutePath)
    }

    @Test
    fun availability_isEmptyWhenNoReadableModelExists() {
        val filesDir = Files.createTempDirectory("gemma-guard-models").toFile()

        val locator = ModelLocator(
            filesDir = filesDir,
            modelFileName = "gemma-4-E2B-it.litertlm",
            debugModelPath = null,
            allowDebugFallback = false,
        )

        val availability = locator.availability()

        assertNull(availability.activeLocation)
    }
}
