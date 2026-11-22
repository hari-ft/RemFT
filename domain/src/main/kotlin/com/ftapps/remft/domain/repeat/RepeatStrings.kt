package com.ftapps.remft.domain.repeat

enum class RepeatString(val key: String) {
    EveryDaily("repeat.every.daily"),
    EveryDailyN("repeat.every.dailyN"),
    EveryWeekly("repeat.every.weekly"),
    EveryWeeklyN("repeat.every.weeklyN"),
    EveryMonthly("repeat.every.monthly"),
    EveryMonthlyN("repeat.every.monthlyN"),
    EveryQuarterly("repeat.every.quarterly"),
    EveryHalfYearly("repeat.every.halfYearly"),
    EveryYearly("repeat.every.yearly"),
    EveryYearlyN("repeat.every.yearlyN"),
    PatternOn("repeat.pattern.on"),
    PatternIn("repeat.pattern.in"),
    PatternCycle("repeat.pattern.cycle"),
    DayOrdinalSt("repeat.dayOrdinal.st"),
    DayOrdinalNd("repeat.dayOrdinal.nd"),
    DayOrdinalRd("repeat.dayOrdinal.rd"),
    DayOrdinalTh("repeat.dayOrdinal.th"),
    OnWeekdays("repeat.on.weekdays"),
    OnMonthDay("repeat.on.monthDay"),
    OnLastDay("repeat.on.lastDay"),
    OnLastDayOf("repeat.on.lastDayOf"),
    OnDayMonth("repeat.on.dayMonth"),
    OnOfMonth("repeat.on.ofMonth"),
    OnNthWeekday("repeat.on.nthWeekday"),
    OnLastWeekday("repeat.on.lastWeekday"),
    OnAfterDay("repeat.on.afterDay"),
    Ordinal1("repeat.ordinal.1"),
    Ordinal2("repeat.ordinal.2"),
    Ordinal3("repeat.ordinal.3"),
    Ordinal4("repeat.ordinal.4"),
    Ordinal5("repeat.ordinal.5"),
    WeekdayMo("repeat.weekday.mo"),
    WeekdayTu("repeat.weekday.tu"),
    WeekdayWe("repeat.weekday.we"),
    WeekdayTh("repeat.weekday.th"),
    WeekdayFr("repeat.weekday.fr"),
    WeekdaySa("repeat.weekday.sa"),
    WeekdaySu("repeat.weekday.su"),
    EndNeverSentence("repeat.end.neverSentence"),
    EndUntilSentence("repeat.end.untilSentence"),
    EndCountSentence("repeat.end.countSentence"),
    AfterDaily("repeat.after.daily"),
    AfterDailyN("repeat.after.dailyN"),
    AfterWeekly("repeat.after.weekly"),
    AfterWeeklyN("repeat.after.weeklyN"),
    AfterMonthly("repeat.after.monthly"),
    AfterMonthlyN("repeat.after.monthlyN");

    companion object {
        fun weekday(code: String): RepeatString = when (code) {
            "MO" -> WeekdayMo
            "TU" -> WeekdayTu
            "WE" -> WeekdayWe
            "TH" -> WeekdayTh
            "FR" -> WeekdayFr
            "SA" -> WeekdaySa
            else -> WeekdaySu
        }

        fun ordinal(n: Int): RepeatString? = when (n) {
            1 -> Ordinal1
            2 -> Ordinal2
            3 -> Ordinal3
            4 -> Ordinal4
            5 -> Ordinal5
            else -> null
        }
    }
}

fun interface RepeatStrings {
    fun template(string: RepeatString): String
}
