package com.ftapps.remft.domain.backup

object PathText {
    fun extension(path: String): String {
        val name = basename(path)
        if (name == "..") return ""
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot)
    }

    fun basename(path: String): String {
        var end = path.length
        while (end > 1 && path[end - 1] == '/') end--
        val trimmed = path.substring(0, end)
        val slash = trimmed.lastIndexOf('/')
        return if (slash < 0) trimmed else trimmed.substring(slash + 1)
    }

    fun posixNormalize(path: String): String {
        if (path.isEmpty()) return "."
        val absolute = path.startsWith("/")
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> when {
                    parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.size - 1)
                    absolute -> Unit
                    else -> parts.add(part)
                }
                else -> parts.add(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    fun isSafeRelative(rel: String): Boolean {
        if (rel.isEmpty()) return false
        val normal = posixNormalize(rel)
        return normal == rel && !normal.startsWith("..") && !normal.startsWith("/") && !normal.contains('\\')
    }
}
