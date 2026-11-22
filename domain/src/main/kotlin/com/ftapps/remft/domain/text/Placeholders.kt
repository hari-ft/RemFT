package com.ftapps.remft.domain.text

private val placeholder = Regex("\\{([A-Za-z0-9_]+)\\}")

fun String.fill(vararg values: Pair<String, Any?>): String {
    if (values.isEmpty()) return this
    val lookup = values.toMap()
    return placeholder.replace(this) { match ->
        val key = match.groupValues[1]
        if (lookup.containsKey(key)) lookup[key].toString() else match.value
    }
}
