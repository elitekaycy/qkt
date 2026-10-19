package com.qkt.common

import java.time.LocalDate

/** Western (Gregorian) Easter, which fixes Good Friday: the one moving full-market closure FX and NYSE share. */
internal object WesternEaster {
    /** Easter Sunday of [year] by the anonymous Gregorian algorithm, e.g. 2024 -> 2024-03-31. */
    fun sunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    /** Good Friday of [year], two days before Easter, e.g. 2024 -> 2024-03-29. */
    fun goodFriday(year: Int): LocalDate = sunday(year).minusDays(2)
}
