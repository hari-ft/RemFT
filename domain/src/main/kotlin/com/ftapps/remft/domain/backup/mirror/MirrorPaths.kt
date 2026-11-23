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
    const val BIN_FOLDER_NAME = "Recycle bin"
    const val BIN_FOLDER = "$ROOT/$BIN_FOLDER_NAME"

    private val unsafe = Regex("[\\x00-\\x1f\\x7f\"*:<>?/\\\\|#%]")
    private val whitespaceRun = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
    private val topNames = setOf(REVIEW_FOLDER_NAME, BIN_FOLDER_NAME.lowercase())
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
        return when (f.targetType) {
            "workspace" -> "${folderPath(f.folderChain)}/$file"
            else -> "${ownerPath(f.targetType, f.ownerTitle, f.ownerId)}/$file"
        }
    }

    fun folderPath(chain: List<String>): String {
        val folders = chain.map { safeSegment(it) }.toMutableList()
        if (folders.isNotEmpty() && folders.first().lowercase() in topNames) folders[0] = "_" + folders[0]
        return (listOf(ROOT) + folders).joinToString("/")
    }

    fun ownerPath(targetType: String, ownerTitle: String?, ownerId: String?): String {
        val owner = safeSegment(ownerTitle ?: ownerId ?: "_")
        return when (targetType) {
            "task" -> "$ROOT/Tasks/$owner"
            "note" -> "$ROOT/Notes/$owner"
            else -> "$ROOT/Other"
        }
    }

    fun binned(path: String): String {
        if (isBinPath(path)) return path
        val rel = relative(path)
        return if (rel.isEmpty()) BIN_FOLDER else "$BIN_FOLDER/$rel"
    }

    fun isBinPath(path: String): Boolean = isUnder(path, BIN_FOLDER)

    fun reviewPath(path: String): String {
        if (isReviewPath(path)) return path
        var rel = if (isMirrorPath(path)) relative(path) else safeSegment(PathText.basename(path), max = 80)
        if (isUnder(rel, BIN_FOLDER_NAME)) rel = rel.substring(BIN_FOLDER_NAME.length).trimStart('/')
        return if (rel.isEmpty()) REVIEW_FOLDER else "$REVIEW_FOLDER/$rel"
    }

    fun fallbackFolders(path: String): List<String> = listOfNotNull(
        parentPath(reviewPath(path)),
        parentPath(binned(path)),
        if (isBinPath(path)) parentPath(ROOT + path.substring(BIN_FOLDER.length)) else null,
        REVIEW_FOLDER,
    ).filter { !it.equals(parentPath(path), ignoreCase = true) }.distinctBy { it.lowercase() }

    fun isUnder(path: String, prefix: String): Boolean {
        if (path.length < prefix.length) return false
        if (!path.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        return path.length == prefix.length || path[prefix.length] == '/'
    }

    fun rebase(path: String, from: String, to: String): String? =
        if (isUnder(path, from)) to + path.substring(from.length) else null

    fun parentPath(path: String): String = path.substringBeforeLast('/', ROOT)

    fun leafName(path: String): String = path.substringAfterLast('/')

    fun depth(path: String): Int = path.count { it == '/' }

    private fun relative(path: String): String =
        if (isUnder(path, ROOT)) path.substring(ROOT.length).trimStart('/') else path

    fun binnedPath(storedName: String): String = "$BIN_FOLDER/${safeSegment(storedName, max = 80)}"

    fun isReviewPath(path: String): Boolean = path.lowercase().startsWith(REVIEW_FOLDER.lowercase() + "/")

    fun reviewName(name: String, n: Int): String {
        if (n <= 1) return name
        val ext = PathText.extension(name)
        val stem = name.substring(0, name.length - ext.length)
        return "$stem ($n)$ext"
    }

    fun folderName(name: String, n: Int): String = if (n <= 1) name else "$name ($n)"

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
