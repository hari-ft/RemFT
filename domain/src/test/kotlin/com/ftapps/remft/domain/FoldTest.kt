package com.ftapps.remft.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldTest {
    @Test
    fun matchesEveryFixturePair() {
        val pairs = Json.parseToJsonElement(Fixtures.text("fold_cases.json")).jsonObject.getValue("pairs").jsonObject
        assertTrue(pairs.isNotEmpty())
        for ((input, expected) in pairs) {
            assertEquals("fold of '$input'", expected.jsonPrimitive.content, Fold.searchText(input))
        }
    }

    @Test
    fun foldsQueryAndStoredTextToTheSameForm() {
        assertEquals(Fold.searchText("Façade İDEA"), Fold.searchText("facade idea"))
    }

    @Test
    fun collapsesUnicodeWhitespaceAndTrims() {
        assertEquals("a b", Fold.searchText("  a  b ﻿"))
    }

    @Test
    fun stripsStrayCombiningDotAbove() {
        assertEquals("idea", Fold.searchText("i̇dea"))
    }

    @Test
    fun keepsNonLatinScriptsCasefolded() {
        assertEquals("дом", Fold.searchText("ДОМ"))
    }
}
