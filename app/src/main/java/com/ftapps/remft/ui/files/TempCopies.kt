package com.ftapps.remft.ui.files

import java.io.File

object TempCopies {
    const val MAX_AGE_MS: Long = 60L * 60 * 1000

    fun pruneStale(root: File, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_AGE_MS) {
        for (entry in root.listFiles().orEmpty()) {
            val newest = entry.walkTopDown().maxOfOrNull { it.lastModified() } ?: continue
            if (nowMs - newest > maxAgeMs) entry.deleteRecursively()
        }
    }
}
