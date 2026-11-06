package com.qkt.connector.mt5

import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.position
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The open positions a session reads back from the venue, keyed by qkt symbol. */
class MT5BrokerVenueReadsTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val books = MT5BrokerState(profile)
    private val reads =
        MT5BrokerVenueReads(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            MT5Symbol(profile.symbolPolicy),
            books.positionBook,
            books.symbolMeta,
        )

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `positions are signed by side and grouped under the qkt symbol`() {
        gateway.on(
            "/get_positions",
            listOf(
                position(1, "0.10", priceOpen = "2400"),
                position(2, "0.30", priceOpen = "2410", type = 1),
                position(3, "1.00", priceOpen = "1.1", symbol = "EURUSDm"),
            ).joinToString(",", "[", "]"),
        )

        val open = reads.getOpenPositions()

        assertThat(open.keys).containsExactlyInAnyOrder("EXNESS:XAUUSD", "EXNESS:EURUSD")
        assertThat(open.getValue("EXNESS:XAUUSD").map { it.quantity.toPlainString() }).containsExactly("0.10", "-0.30")
        assertThat(open.getValue("EXNESS:XAUUSD")[1].avgEntryPrice).isEqualByComparingTo("2410")
        assertThat(open.getValue("EXNESS:EURUSD").single().symbol).isEqualTo("EXNESS:EURUSD")
    }

    @Test
    fun `a failed read is an error naming why, never an empty book`() {
        gateway.fail("/get_positions", 503)

        assertThatThrownBy { reads.getOpenPositions() }
            .hasMessageContaining("getOpenPositions: gateway read failed")
            .hasMessageContaining("HTTP 503")
    }
}
