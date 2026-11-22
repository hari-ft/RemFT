package com.ftapps.remft.data.files

object MimeTypes {
    const val FALLBACK = "application/octet-stream"

    private val byExtension = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "m4v" to "video/x-m4v",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    fun forName(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot < 0) "" else name.substring(dot + 1).lowercase()
        return byExtension[ext] ?: FALLBACK
    }

    fun extensionFor(mime: String?): String? = byExtension.entries.firstOrNull { it.value == mime }?.key

    fun isImage(mime: String): Boolean = mime.startsWith("image/")
}
