package com.qkt.marketdata.store.binance

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BinanceQuarterlyTest {
    @Test
    fun `a quarterly code expires at 08-00 UTC on its date`() {
        assertThat(
            BinanceQuarterly.expiryMs("BTCUSDT_240927"),
        ).isEqualTo(Instant.parse("2024-09-27T08:00:00Z").toEpochMilli())
    }

    @Test
    fun `perpetuals and malformed codes are not quarterlies`() {
        listOf("BTCUSDT", "BTCUSDT_PERP", "BTCUSDT_24092", "BTCUSDT_241332", "_240927").forEach {
            assertThat(BinanceQuarterly.expiryMs(it)).describedAs(it).isNull()
        }
    }
}
