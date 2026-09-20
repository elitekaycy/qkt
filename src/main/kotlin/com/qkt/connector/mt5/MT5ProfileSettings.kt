package com.qkt.connector.mt5

import com.qkt.broker.OrderTypeCapability
import com.qkt.common.TradingCalendar
import java.math.BigDecimal

/** The settings an MT5 `brokers:` entry understands, and the parsers for its nested blocks. */
internal object MT5ProfileSettings {
    /** Every scalar key [MT5BrokerProfileLoader] reads; any other key in an entry is refused. */
    val KEYS: Set<String> =
        setOf(
            "extends",
            "gateway_url",
            "api_key",
            "symbol_suffix",
            "magic",
            "server_time_zone",
            "server_tz_offset_hours",
            "poll_interval_ms",
            "tick_poll_interval_ms",
            "http_timeout_ms",
            "retry_attempts",
            "deviation_points",
            "expected_account_login",
            "expected_account_server",
            "expected_trade_mode",
            "expected_account_currency",
            "expected_leverage",
            "expected_margin_mode",
        )

    private val INSTRUMENT_KEYS: Set<String> =
        setOf("min_volume", "volume_step", "point_size", "digits", "trade_stops_level_points", "max_volume")

    fun calendarByName(
        profile: String,
        cal: String,
    ): TradingCalendar {
        val spec = cal.trim()
        com.qkt.common.DailyBreakCalendar
            .parse(spec) { base -> baseCalendarByName(profile, base) }
            ?.let { return it }
        return baseCalendarByName(profile, spec)
    }

    private fun baseCalendarByName(
        profile: String,
        cal: String,
    ): TradingCalendar =
        when (cal.trim().lowercase()) {
            "fx" -> TradingCalendar.fxDefault()
            "crypto" -> TradingCalendar.crypto()
            "nyse" -> TradingCalendar.nyse()
            else ->
                error(
                    "MT5 profile '$profile' has unknown calendar '$cal' " +
                        "(expected fx|crypto|nyse, optionally '<base> pause HH:MM-HH:MM [Zone]')",
                )
        }

    fun parseCapability(
        profile: String,
        cap: String,
    ): OrderTypeCapability =
        runCatching { OrderTypeCapability.valueOf(cap.trim().uppercase()) }
            .getOrElse { error("MT5 profile '$profile' has unknown capability '$cap'") }

    fun parseInstrumentSpec(
        profile: String,
        symbol: String,
        spec: Map<String, String>,
    ): InstrumentSpec {
        val unknown = spec.keys - INSTRUMENT_KEYS
        require(unknown.isEmpty()) {
            "MT5 profile '$profile' instrument '$symbol' has unknown field(s) ${unknown.sorted()}; known: ${INSTRUMENT_KEYS.sorted()}"
        }

        fun req(key: String): String = spec[key] ?: error("MT5 profile '$profile' instrument '$symbol' missing '$key'")
        return InstrumentSpec(
            minVolume = BigDecimal(req("min_volume")),
            volumeStep = BigDecimal(req("volume_step")),
            pointSize = BigDecimal(req("point_size")),
            digits = req("digits").toInt(),
            tradeStopsLevelPoints = req("trade_stops_level_points").toInt(),
            maxVolume = spec["max_volume"]?.let(::BigDecimal),
        )
    }
}
