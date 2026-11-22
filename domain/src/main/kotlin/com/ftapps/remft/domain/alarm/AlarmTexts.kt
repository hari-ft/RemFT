package com.ftapps.remft.domain.alarm

import com.ftapps.remft.domain.text.fill

data class AlarmTexts(
    val privacyTitle: String,
    val privateBody: String,
    val urgentFirst: String,
    val urgentRepeat: String,
    val dueNow: String,
    val afterSnooze: String,
    val reminder: String,
    val testTitle: String,
    val testBody: String,
) {
    fun urgentTitle(taskTitle: String): String = "$URGENT_TITLE_MARK$taskTitle"

    fun afterSnoozeBody(round: Int): String = afterSnooze.fill("count" to round)

    fun urgentRepeatBody(alert: Int): String = urgentRepeat.fill("count" to alert)

    companion object {
        const val URGENT_TITLE_MARK = "⏰ "
    }
}
