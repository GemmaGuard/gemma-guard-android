package com.gemmaguard.app.inference

import java.io.File

data class ModelAvailability(
    val activeLocation: ModelLocation?,
    val appInternalTarget: File,
    val debugExternalTarget: File?,
)
