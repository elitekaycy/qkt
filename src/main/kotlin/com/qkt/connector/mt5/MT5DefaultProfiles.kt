package com.qkt.connector.mt5

import com.qkt.common.DailyBreakCalendar
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import java.time.LocalTime
import java.time.ZoneId

/**
 * Built-in [MT5BrokerProfile] templates for common brokers.
 *
 * Users can `extends:` one of these in `qkt.config.yaml` to inherit the symbol policy,
 * server timezone, and other broker-specific defaults — then override only what
 * differs (gateway URL, magic number, restrictions).
 */
object MT5DefaultProfiles {
    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    private val LONDON: ZoneId = ZoneId.of("Europe/London")

    private fun pause(
        base: TradingCalendar,
        start: LocalTime,
        end: LocalTime,
        zone: ZoneId,
    ): TradingCalendar = DailyBreakCalendar(base, start, end, zone)

    private fun metalsEnergy(
        pause: TradingCalendar,
        default: TradingCalendar,
        extra: List<SymbolCalendars.Rule> = emptyList(),
    ): SymbolCalendars =
        SymbolCalendars(
            extra + METALS_ENERGY.map { SymbolCalendars.Rule(it, pause) },
            default = default,
        )

    private val METALS_ENERGY = listOf("XAU*", "XAG*", "XPT*", "XPD*", "USOIL*", "UKOIL*", "XTI*", "XBR*", "XNG*")
    private val CRYPTO = listOf("BTC*", "ETH*", "LTC*", "XRP*", "BCH*", "SOL*", "ADA*", "DOGE*", "DOT*", "LINK*")

    /**
     * Spot metals and energy pause daily around the 17:00 New York close; FX majors pause for the
     * rollover. Measured quote gaps (UTC, summer time) on Exness and IC Markets: metals last print
     * 20:57:58-20:59:00, first print 22:01:06-22:06; FX rollover gaps a median 4 min, up to 11.
     *
     * The gate classifies a gap when it notices it, one stale threshold (60-120 s) after the last
     * print, so the windows open before the market goes quiet and close after it reopens. A window
     * only decides PAUSED versus STALE; new orders wait either way, and polling is unaffected.
     */
    private val NY_CLOSE_METALS =
        pause(TradingCalendar.fxDefault(), LocalTime.of(16, 55), LocalTime.of(18, 10), NEW_YORK)
    private val NY_CLOSE_FX = pause(TradingCalendar.fxDefault(), LocalTime.of(16, 55), LocalTime.of(17, 15), NEW_YORK)

    /** Exness copper follows London: last print 18:55 BST, first print 01:02 BST. */
    private val EXNESS_COPPER = pause(TradingCalendar.fxDefault(), LocalTime.of(18, 50), LocalTime.of(1, 10), LONDON)

    /**
     * Exness quotes crypto through the weekend; left on the FX calendar, a BTC-only feed stopped
     * polling from Friday 22:00 to Sunday 22:00 (48 h blind on the forward bench, 2026-09-18).
     */
    private val EXNESS_CALENDARS =
        metalsEnergy(
            NY_CLOSE_METALS,
            default = NY_CLOSE_FX,
            extra =
                CRYPTO.map { SymbolCalendars.Rule(it, TradingCalendar.crypto()) } +
                    SymbolCalendars.Rule("XCU*", EXNESS_COPPER),
        )

    private val ICMARKETS_CALENDARS = metalsEnergy(NY_CLOSE_METALS, default = NY_CLOSE_FX)

    /**
     * The5ers (FivePercentOnline) closes earlier and reopens later than Exness: metals last print
     * 20:49:51-20:50:00, first print 22:05:03-22:05:58; FX and BTC 20:54:41-20:55:00 to
     * 21:05:00-21:05:59 (UTC, 2026-09-10..24). BTC stops for the weekend here, so it stays on FX.
     */
    private val THE5ERS_CALENDARS =
        metalsEnergy(
            pause(TradingCalendar.fxDefault(), LocalTime.of(16, 45), LocalTime.of(18, 10), NEW_YORK),
            default = pause(TradingCalendar.fxDefault(), LocalTime.of(16, 50), LocalTime.of(17, 10), NEW_YORK),
        )

    /**
     * Exness — adds `m` suffix to FX, maps NAS100→USTEC, and uses a UTC server clock.
     * Exness runs GMT+0 servers, unlike the New York-close (UTC+2/+3) clock most forex
     * brokers use; measured against Exness-MT5Trial9, raw tick epochs match UTC (#812).
     */
    val exness =
        MT5BrokerProfile(
            name = "exness",
            gatewayUrl = "http://localhost:5001",
            symbolPolicy =
                SymbolPolicy(
                    suffix = "m",
                    aliases =
                        mapOf(
                            "NAS100" to "USTEC",
                            "US500" to "US500",
                            "US30" to "US30",
                            "UKOIL" to "XBRUSD",
                            "NGAS" to "XNGUSD",
                        ),
                ),
            serverTimeZone = MT5ServerTimeZone.UTC,
            magic = 10001,
            symbolCalendars = EXNESS_CALENDARS,
        )

    val icmarkets =
        MT5BrokerProfile(
            name = "icmarkets",
            gatewayUrl = "http://localhost:5002",
            symbolPolicy = SymbolPolicy(suffix = ".raw"),
            serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
            magic = 10002,
            symbolCalendars = ICMARKETS_CALENDARS,
        )

    val ftmo =
        MT5BrokerProfile(
            name = "ftmo",
            gatewayUrl = "http://localhost:5003",
            symbolPolicy = SymbolPolicy(suffix = ""),
            serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
            magic = 10003,
        )

    val pepperstone =
        MT5BrokerProfile(
            name = "pepperstone",
            gatewayUrl = "http://localhost:5004",
            symbolPolicy = SymbolPolicy(suffix = ".cmd"),
            serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
            magic = 10004,
        )

    /** The5ers prop accounts: bare symbols on a New York-close (UTC+2/+3) server. */
    val the5ers =
        MT5BrokerProfile(
            name = "the5ers",
            gatewayUrl = "http://localhost:5005",
            symbolPolicy = SymbolPolicy(suffix = ""),
            serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
            magic = 10005,
            symbolCalendars = THE5ERS_CALENDARS,
        )

    val all: Map<String, MT5BrokerProfile> =
        listOf(exness, icmarkets, ftmo, pepperstone, the5ers).associateBy { it.name }
}
