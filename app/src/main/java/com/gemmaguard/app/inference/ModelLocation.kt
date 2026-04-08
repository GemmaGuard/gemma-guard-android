package com.gemmaguard.app.inference

import java.io.File

data class ModelLocation(
    val file: File,
    val source: ModelSource,
)
