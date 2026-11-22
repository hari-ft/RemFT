package com.ftapps.remft.domain

data class AppVersion(val major: Int, val minor: Int, val bugfix: Int) : Comparable<AppVersion> {
    init {
        require(major in 1..99 && minor in 1..9 && bugfix in 1..9) { "version out of range" }
    }

    val code: Int get() = major * 100 + minor * 10 + bugfix

    fun next(): AppVersion = when {
        bugfix < 9 -> copy(bugfix = bugfix + 1)
        minor < 9 -> AppVersion(major, minor + 1, 1)
        else -> AppVersion(major + 1, 1, 1)
    }

    override fun compareTo(other: AppVersion): Int = code.compareTo(other.code)

    override fun toString(): String = "$major.$minor.$bugfix"

    companion object {
        fun parse(text: String): AppVersion {
            val parts = text.trim().split('.').map { it.toInt() }
            require(parts.size == 3) { "expected Major.Minor.Bugfix" }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}
