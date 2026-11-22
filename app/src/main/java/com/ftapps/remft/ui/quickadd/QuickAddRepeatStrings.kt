package com.ftapps.remft.ui.quickadd

import android.content.res.Resources
import androidx.annotation.StringRes
import com.ftapps.remft.R
import com.ftapps.remft.domain.repeat.RepeatString
import com.ftapps.remft.domain.repeat.RepeatStrings

class QuickAddRepeatStrings(private val resources: Resources) : RepeatStrings {
    override fun template(string: RepeatString): String = resources.getString(resourceOf(string))

    companion object {
        @StringRes
        fun resourceOf(string: RepeatString): Int = when (string) {
            RepeatString.EveryDaily -> R.string.repeat_every_daily
            RepeatString.EveryDailyN -> R.string.repeat_every_dailyN
            RepeatString.EveryWeekly -> R.string.repeat_every_weekly
            RepeatString.EveryWeeklyN -> R.string.repeat_every_weeklyN
            RepeatString.EveryMonthly -> R.string.repeat_every_monthly
            RepeatString.EveryMonthlyN -> R.string.repeat_every_monthlyN
            RepeatString.EveryQuarterly -> R.string.repeat_every_quarterly
            RepeatString.EveryHalfYearly -> R.string.repeat_every_halfYearly
            RepeatString.EveryYearly -> R.string.repeat_every_yearly
            RepeatString.EveryYearlyN -> R.string.repeat_every_yearlyN
            RepeatString.PatternOn -> R.string.repeat_pattern_on
            RepeatString.PatternIn -> R.string.repeat_pattern_in
            RepeatString.PatternCycle -> R.string.repeat_pattern_cycle
            RepeatString.DayOrdinalSt -> R.string.repeat_dayOrdinal_st
            RepeatString.DayOrdinalNd -> R.string.repeat_dayOrdinal_nd
            RepeatString.DayOrdinalRd -> R.string.repeat_dayOrdinal_rd
            RepeatString.DayOrdinalTh -> R.string.repeat_dayOrdinal_th
            RepeatString.OnWeekdays -> R.string.repeat_on_weekdays
            RepeatString.OnMonthDay -> R.string.repeat_on_monthDay
            RepeatString.OnLastDay -> R.string.repeat_on_lastDay
            RepeatString.OnLastDayOf -> R.string.repeat_on_lastDayOf
            RepeatString.OnDayMonth -> R.string.repeat_on_dayMonth
            RepeatString.OnOfMonth -> R.string.repeat_on_ofMonth
            RepeatString.OnNthWeekday -> R.string.repeat_on_nthWeekday
            RepeatString.OnLastWeekday -> R.string.repeat_on_lastWeekday
            RepeatString.OnAfterDay -> R.string.repeat_on_afterDay
            RepeatString.Ordinal1 -> R.string.repeat_ordinal_1
            RepeatString.Ordinal2 -> R.string.repeat_ordinal_2
            RepeatString.Ordinal3 -> R.string.repeat_ordinal_3
            RepeatString.Ordinal4 -> R.string.repeat_ordinal_4
            RepeatString.Ordinal5 -> R.string.repeat_ordinal_5
            RepeatString.WeekdayMo -> R.string.repeat_weekday_mo
            RepeatString.WeekdayTu -> R.string.repeat_weekday_tu
            RepeatString.WeekdayWe -> R.string.repeat_weekday_we
            RepeatString.WeekdayTh -> R.string.repeat_weekday_th
            RepeatString.WeekdayFr -> R.string.repeat_weekday_fr
            RepeatString.WeekdaySa -> R.string.repeat_weekday_sa
            RepeatString.WeekdaySu -> R.string.repeat_weekday_su
            RepeatString.EndNeverSentence -> R.string.repeat_end_neverSentence
            RepeatString.EndUntilSentence -> R.string.repeat_end_untilSentence
            RepeatString.EndCountSentence -> R.string.repeat_end_countSentence
            RepeatString.AfterDaily -> R.string.repeat_after_daily
            RepeatString.AfterDailyN -> R.string.repeat_after_dailyN
            RepeatString.AfterWeekly -> R.string.repeat_after_weekly
            RepeatString.AfterWeeklyN -> R.string.repeat_after_weeklyN
            RepeatString.AfterMonthly -> R.string.repeat_after_monthly
            RepeatString.AfterMonthlyN -> R.string.repeat_after_monthlyN
        }
    }
}
