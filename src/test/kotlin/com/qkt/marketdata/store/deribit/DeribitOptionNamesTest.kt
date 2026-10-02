package com.qkt.marketdata.store.deribit

import com.qkt.instrument.OptionContract
import com.qkt.instrument.OptionRight
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeribitOptionNamesTest {
    @Test
    fun `a deribit option name gives its underlying, expiry at 08 00 UTC, strike and right`() {
        val parsed = DeribitOptionNames.parse("BTC_USDC-27SEP24-60000-C")

        assertThat(parsed).isEqualTo(
            OptionContract(
                "BTC_USDC-27SEP24-60000-C",
                BigDecimal("60000"),
                OptionRight.CALL,
                Instant.parse("2024-09-27T08:00:00Z").toEpochMilli(),
            ),
        )
        assertThat(DeribitOptionNames.underlyingOf("BTC_USDC-27SEP24-60000-C")).isEqualTo("BTC_USDC")
    }

    @Test
    fun `decimal strikes use d and single-digit days parse`() {
        val parsed = requireNotNull(DeribitOptionNames.parse("AVAX_USDC-1OCT26-9d5-P"))

        assertThat(parsed.strike).isEqualByComparingTo("9.5")
        assertThat(parsed.right).isEqualTo(OptionRight.PUT)
        assertThat(parsed.expiryMs).isEqualTo(Instant.parse("2026-10-01T08:00:00Z").toEpochMilli())
    }

    @Test
    fun `names that are not options are not parsed`() {
        assertThat(DeribitOptionNames.parse("BTC-PERPETUAL")).isNull()
        assertThat(DeribitOptionNames.parse("BTC_USDC-27XYZ24-60000-C")).isNull()
        assertThat(DeribitOptionNames.parse("BTC_USDC-27SEP24-60000-X")).isNull()
        assertThat(DeribitOptionNames.parse("BTC_USDC-27SEP24-0-C")).isNull()
    }
}
