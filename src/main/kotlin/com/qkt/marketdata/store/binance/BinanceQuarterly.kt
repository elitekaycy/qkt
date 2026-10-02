package com.qkt.marketdata.store.binance

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Binance USDⓈ-M quarterly contract codes, `ROOT_YYMMDD` (e.g. `BTCUSDT_240927`). Quarterlies
 * deliver at 08:00 UTC on the coded date.
 */
object BinanceQuarterly {
    private val CODE = Regex("([A-Z0-9]+)_(\\d{6})")
    private val DATE = DateTimeFormatter.ofPattern("uuMMdd")
    private val DELIVERY = LocalTime.of(8, 0)

    /** Delivery time of [contract] in UTC epoch millis, or null when it is not a quarterly code. */
    fun expiryMs(contract: String): Long? {
        val date = CODE.matchEntire(contract)?.groupValues?.get(2) ?: return null
        val day =
            try {
                LocalDate.parse(date, DATE)
            } catch (e: DateTimeParseException) {
                return null
            }
        return day.atTime(DELIVERY).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
