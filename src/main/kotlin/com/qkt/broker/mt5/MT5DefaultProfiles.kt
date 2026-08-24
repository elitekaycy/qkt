package com.qkt.broker.mt5

/**
 * Built-in [MT5BrokerProfile] templates for common brokers.
 *
 * Users can `extends:` one of these in `qkt.config.yaml` to inherit the symbol policy,
 * server timezone, and other broker-specific defaults — then override only what
 * differs (gateway URL, magic number, restrictions).
 */
object MT5DefaultProfiles {
    private val NY_CLOSE_BREAK: com.qkt.common.TradingCalendar =
        com.qkt.common.DailyBreakCalendar(
            base =
                com.qkt.common.TradingCalendar
                    .fxDefault(),
            breakStart = java.time.LocalTime.of(17, 0),
            breakEnd = java.time.LocalTime.of(18, 0),
            zone = java.time.ZoneId.of("America/New_York"),
        )

    /**
     * Spot metals and energy CFDs pause daily at the 17:00 New York close for an hour —
     * measured on Exness and IC Markets as a 20:58–22:01 UTC quote gap in August (21:00
     * in summer time, 22:00 in winter). FX majors and copper quote straight through.
     */
    private val METALS_ENERGY_BREAK: SymbolCalendars =
        SymbolCalendars(
            listOf(
                SymbolCalendars.Rule("XAU*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("XAG*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("XPT*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("XPD*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("USOIL*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("UKOIL*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("XTI*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("XBR*", NY_CLOSE_BREAK),
                SymbolCalendars.Rule("XNG*", NY_CLOSE_BREAK),
            ),
            default =
                com.qkt.common.TradingCalendar
                    .fxDefault(),
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
            symbolCalendars = METALS_ENERGY_BREAK,
        )

    val icmarkets =
        MT5BrokerProfile(
            name = "icmarkets",
            gatewayUrl = "http://localhost:5002",
            symbolPolicy = SymbolPolicy(suffix = ".raw"),
            serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
            magic = 10002,
            symbolCalendars = METALS_ENERGY_BREAK,
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

    val all: Map<String, MT5BrokerProfile> =
        listOf(exness, icmarkets, ftmo, pepperstone).associateBy { it.name }
}
