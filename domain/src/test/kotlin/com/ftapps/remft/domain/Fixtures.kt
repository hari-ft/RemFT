package com.ftapps.remft.domain

import java.io.File

object Fixtures {
    private val dir: File
        get() = File(System.getProperty("fixtures.dir") ?: error("fixtures.dir not set"))

    fun text(name: String): String = dir.resolve(name).readText(Charsets.UTF_8)
}
