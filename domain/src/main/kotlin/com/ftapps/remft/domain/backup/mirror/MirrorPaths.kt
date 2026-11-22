package com.ftapps.remft.domain.backup.mirror

import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.text.Whitespace


data class MirrorPathInput(
    val id: String,
    val name: String,
    val storedName: String,
    val targetType: String,
    val folderChain: List<String> = emptyList(),
    val ownerTitle: String? = null,
    val ownerId: String? = null,
)

object MirrorPaths {
    const val ROOT = "Files"
    const val REVIEW_FOLDER_NAME = "reviewneeded"
    const val REVIEW_FOLDER = "$ROOT/$REVIEW_FOLDER_NAME"

    private val unsafe = Regex("[\\x00-\\x1f\\x7f\"*:<>?/\\\\|#%]")
    private val whitespaceRun = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
    private val reservedDevice = Regex("^(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?$")

    fun safeSegment(raw: String, max: Int = 60): String {
        var s = Whitespace.trim(whitespaceRun.replace(unsafe.replace(raw, "_"), " "))
        val codePoints = s.codePoints().toArray()
        if (codePoints.size > max) s = Whitespace.trim(String(codePoints, 0, max))
        s = stripTrailingDotsSpaces(s)
        if (s.isEmpty()) return "_"
        val lower = s.lowercase()
        val reserved = reservedDevice.matches(lower) ||
            lower == ".lock" ||
            lower == "desktop.ini" ||
            lower.startsWith("_vti_") ||
            lower.startsWith("~$")
        return if (reserved) "_$s" else s
    }

    fun fileName(id: String, name: String, storedName: String): String {
        val ext = PathText.extension(storedName)
        val base = if (ext.isNotEmpty() && name.lowercase().endsWith(ext.lowercase())) {
            name.substring(0, name.length - ext.length)
        } else {
            name
        }
        return "${safeSegment(base)} (${safeSegment(id)})$ext"
    }

    fun pathFor(f: MirrorPathInput): String {
        val file = fileName(f.id, f.name, f.storedName)
        val owner = safeSegment(f.ownerTitle ?: f.ownerId ?: "_")
        val folders = f.folderChain.map { safeSegment(it) }.toMutableList()
        if (folders.isNotEmpty() && folders.first().lowercase() == REVIEW_FOLDER_NAME) folders[0] = "_" + folders[0]
        return when (f.targetType) {
            "workspace" -> (listOf(ROOT) + folders + file).joinToString("/")
            "task" -> "$ROOT/Tasks/$owner/$file"
            "note" -> "$ROOT/Notes/$owner/$file"
            else -> "$ROOT/Other/$file"
        }
    }

    fun binnedPath(storedName: String): String = "$ROOT/Recycle bin/${safeSegment(storedName, max = 80)}"

    fun isReviewPath(path: String): Boolean = path.lowercase().startsWith(REVIEW_FOLDER.lowercase() + "/")

    fun reviewName(name: String, n: Int): String {
        if (n <= 1) return name
        val ext = PathText.extension(name)
        val stem = name.substring(0, name.length - ext.length)
        return "$stem ($n)$ext"
    }

    fun isReviewNameOf(candidate: String, name: String): Boolean {
        val c = candidate.lowercase()
        val n = name.lowercase()
        if (c == n) return true
        val ext = PathText.extension(n)
        val stem = n.substring(0, n.length - ext.length)
        return Regex(Regex.escape(stem) + " \\(\\d+\\)" + Regex.escape(ext)).matches(c)
    }

    fun isMirrorPath(path: String): Boolean {
        val parts = path.split('/')
        return parts.size >= 2 && parts.first() == ROOT && parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun stripTrailingDotsSpaces(s: String): String {
        var end = s.length
        while (end > 0 && (s[end - 1] == '.' || s[end - 1] == ' ')) end--
        return s.substring(0, end)
    }
}
