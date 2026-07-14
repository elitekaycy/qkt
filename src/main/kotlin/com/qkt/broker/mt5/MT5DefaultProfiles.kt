package com.qkt.broker.mt5

/**
 * Built-in [MT5BrokerProfile] templates for common brokers.
 *
 * Users can `extends:` one of these in `qkt.config.yaml` to inherit the symbol policy,
 * server timezone, and other broker-specific defaults — then override only what
 * differs (gateway URL, magic number, restrictions).
 */
object MT5DefaultProfiles {
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
        )

    val icmarkets =
        MT5BrokerProfile(
            name = "icmarkets",
            gatewayUrl = "http://localhost:5002",
            symbolPolicy = SymbolPolicy(suffix = ".raw"),
            serverTimeZone = MT5ServerTimeZone.NEW_YORK_CLOSE,
            magic = 10002,
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
