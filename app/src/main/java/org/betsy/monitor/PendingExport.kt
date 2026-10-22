package org.betsy.monitor

import android.content.Context
import java.io.File
import java.util.UUID

/** A document picker can outlive the Activity or process. Keep payloads out of the Bundle. */
class PendingExport(
    private val context: Context,
) {
    private val folder get() = File(context.cacheDir, "pending-exports").apply { mkdirs() }

    fun stage(text: String): String {
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000L }?.forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        file(token).writeText(text)
        return token
    }

    private fun file(token: String): File {
        require(token.matches(Regex("[a-f0-9-]{36}"))) { "Export invalid" }
        return File(folder, token)
    }

    fun read(token: String): String = file(token).readText()

    fun discard(token: String) {
        file(token).delete()
    }
}
