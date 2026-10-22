package org.betsy.monitor

import android.content.Context
import org.betsy.BuildConfig
import java.io.File
import java.util.UUID

class CaptureStore(
    private val context: Context,
) {
    private val folder get() = File(context.filesDir, "elm-captures").apply { mkdirs() }

    @Volatile var lastError = ""
        private set

    fun list(): List<File> = folder.listFiles()?.filter { it.extension == "log" }?.sortedByDescending { it.name } ?: emptyList()

    fun start(transport: String): SessionCapture? =
        try {
            lastError = ""
            list().drop(9).forEach { it.delete() }
            val file = File(folder, "elm-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.log")
            SessionCapture(file, onError = { lastError = it }).also {
                it.append("BUILD", "${BuildConfig.BUILD_LABEL}; transport $transport; Toyota Prius 30 selected")
            }
        } catch (e: Exception) {
            lastError = "Jurnalul tehnic nu a putut porni: ${e.message ?: e.javaClass.simpleName}"
            null
        }
}
