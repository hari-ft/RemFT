package com.ftapps.remft.domain.tasks

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.time.DateTimeException
import java.time.LocalDate

sealed interface DeepLinkTarget {
    data object Home : DeepLinkTarget
    data object Add : DeepLinkTarget
    data class Day(val date: LocalDate) : DeepLinkTarget
    data class OpenTask(val id: String) : DeepLinkTarget
    data object Files : DeepLinkTarget
}

object DeepLinks {
    const val SCHEME = "remft"
    const val ALARM_REFRESH_HOST = "refresh-alarms"

    private val dayPattern = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val ulidPattern = Regex("""^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{26}$""")
    private val schemePrefix = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")

    private class Parsed(val scheme: String, val host: String, val segments: List<String>, val query: String)

    fun dayParam(day: LocalDate): String =
        "${day.year.toString().padStart(4, '0')}-" +
            "${day.monthValue.toString().padStart(2, '0')}-" +
            day.dayOfMonth.toString().padStart(2, '0')

    fun parseDay(raw: String?): LocalDate? {
        if (raw == null) return null
        val match = dayPattern.matchEntire(raw) ?: return null
        val (year, month, dayOfMonth) = match.destructured
        return try {
            LocalDate.of(year.toInt(), month.toInt(), dayOfMonth.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    fun routeFor(uri: String): DeepLinkTarget? {
        val parsed = parse(uri) ?: return null
        if (parsed.scheme != SCHEME) return null
        val segments = buildList {
            if (parsed.host.isNotEmpty()) add(parsed.host)
            addAll(parsed.segments)
        }
        if (segments.isEmpty()) return null
        return when (segments.first()) {
            "open" -> if (segments.size == 1) DeepLinkTarget.Home else null
            "add" -> if (segments.size == 1 && parsed.query.isEmpty()) DeepLinkTarget.Add else null
            "day" -> {
                if (segments.size != 2 || parsed.query.isNotEmpty()) return null
                parseDay(segments[1])?.let { DeepLinkTarget.Day(it) }
            }
            "task" -> if (segments.size == 2 && ulidPattern.matches(segments[1])) DeepLinkTarget.OpenTask(segments[1]) else null
            "file" -> if (segments.size == 2 && ulidPattern.matches(segments[1])) DeepLinkTarget.Files else null
            else -> null
        }
    }

    fun appLinkTarget(uri: String): DeepLinkTarget? {
        val scheme = parse(uri)?.scheme ?: schemePrefix.find(uri)?.groupValues?.get(1)?.lowercase() ?: return null
        if (scheme != SCHEME) return null
        return routeFor(uri) ?: DeepLinkTarget.Home
    }

    fun isUnknownAppLink(uri: String): Boolean = appLinkTarget(uri) != null && routeFor(uri) == null

    fun isAlarmRefresh(uri: String): Boolean {
        val parsed = parse(uri) ?: return false
        return parsed.scheme == SCHEME && parsed.host == ALARM_REFRESH_HOST
    }

    private fun decode(segment: String): String =
        try {
            URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            segment
        }

    private fun segmentsOf(rawPath: String): List<String> =
        rawPath.split('/').filter { it.isNotEmpty() }.map(::decode)

    private fun parse(raw: String): Parsed? {
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (uri.isOpaque) {
            val ssp = uri.rawSchemeSpecificPart ?: ""
            val queryAt = ssp.indexOf('?')
            val path = if (queryAt < 0) ssp else ssp.substring(0, queryAt)
            val query = if (queryAt < 0) "" else ssp.substring(queryAt + 1)
            return Parsed(scheme, "", segmentsOf(path), query)
        }
        val host = (uri.host ?: uri.authority?.substringAfterLast('@')?.substringBefore(':') ?: "").lowercase()
        return Parsed(scheme, host, segmentsOf(uri.rawPath ?: ""), uri.rawQuery ?: "")
    }
}
