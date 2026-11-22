package com.ftapps.remft.domain.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

object JsonText {
    private val compact = Json
    private val pretty = Json { prettyPrint = true }
    private val integerLiteral = Regex("-?(0|[1-9][0-9]*)")
    private val numberLiteral = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun parse(text: String): JsonElement = compact.parseToJsonElement(text)

    fun parseOrNull(text: String): JsonElement? = runCatching { parse(text) }.getOrNull()

    fun encode(element: JsonElement): String = compact.encodeToString(JsonElement.serializer(), element)

    fun encodePretty(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

    fun intOf(element: JsonElement?): Long? {
        if (element !is JsonPrimitive || element.isString || element is JsonNull) return null
        if (!integerLiteral.matches(element.content)) return null
        return element.content.toLongOrNull()
    }

    fun numberOf(element: JsonElement?): Double? {
        if (element !is JsonPrimitive || element.isString || element is JsonNull) return null
        if (!numberLiteral.matches(element.content)) return null
        return element.content.toDoubleOrNull()
    }

    fun stringOf(element: JsonElement?): String? {
        if (element !is JsonPrimitive || !element.isString) return null
        return element.content
    }

    fun boolOf(element: JsonElement?): Boolean? {
        if (element !is JsonPrimitive || element.isString || element is JsonNull) return null
        return when (element.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun isNull(element: JsonElement?): Boolean = element == null || element is JsonNull
}
