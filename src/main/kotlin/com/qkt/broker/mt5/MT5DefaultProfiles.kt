package com.qkt.broker.mt5

object MT5DefaultProfiles {
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
            serverTzOffsetHours = 2,
            magic = 10001,
        )

    val icmarkets =
        MT5BrokerProfile(
            name = "icmarkets",
            gatewayUrl = "http://localhost:5002",
            symbolPolicy = SymbolPolicy(suffix = ".raw"),
            serverTzOffsetHours = 3,
            magic = 10002,
        )

    val ftmo =
        MT5BrokerProfile(
            name = "ftmo",
            gatewayUrl = "http://localhost:5003",
            symbolPolicy = SymbolPolicy(suffix = ""),
            serverTzOffsetHours = 2,
            magic = 10003,
        )

    val pepperstone =
        MT5BrokerProfile(
            name = "pepperstone",
            gatewayUrl = "http://localhost:5004",
            symbolPolicy = SymbolPolicy(suffix = ".cmd"),
            serverTzOffsetHours = 2,
            magic = 10004,
        )

    val all: Map<String, MT5BrokerProfile> =
        listOf(exness, icmarkets, ftmo, pepperstone).associateBy { it.name }
}
