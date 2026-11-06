package com.qkt.connector.mt5

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.deal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** What deal history and the live quote say about closing ticket 9001. */
class MT5CloseVenueTruthTest {
    private val now = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val books = MT5BrokerState(profile)
    private val truth =
        MT5CloseVenueTruth(
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            FixedClock(time = now),
            books,
            priceTracker = null,
            retryBackoffMs = 100L,
        )

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `when the position's own deal query fails the account's deals stand in, narrowed to the position`() {
        val accountDeals =
            listOf(
                deal(7, 9001, entry = 1, volume = "0.10", price = "2410", type = 1, commission = "-0.70", timeMs = now),
                deal(8, 9002, entry = 1, volume = "0.10", price = "2300", type = 1, commission = "-5", timeMs = now),
            ).joinToString(",", "[", "]")
        gateway.respond("/history_deals_get", 500 to "{}", 200 to accountDeals)
        val ack = MT5OrderResult(retcode = 10009, order = 0L, deal = 7L, price = BigDecimal.ZERO, comment = "")

        val result = truth.venueTruthForPositionClose(9001L, ack, closeStartedAtMs = now, positionClosed = true)

        assertThat(result.costs).isEqualByComparingTo("0.70")
        assertThat(result.closingDealPrice).isEqualByComparingTo("2410")
        val (byPosition, byAccount) = gateway.calls("/history_deals_get")
        assertThat(byPosition).contains("position=9001")
        assertThat(byAccount).doesNotContain("position=")
    }

    @Test
    fun `with no engine quote the live tick prices a close, bid to sell and ask to buy`() {
        gateway.on("/symbol_info_tick/XAUUSDm", """{"bid":2400.1,"ask":2400.3,"time":1}""")

        assertThat(truth.marketClosePrice("EXNESS:XAUUSD", "XAUUSDm", Side.SELL)).isEqualByComparingTo("2400.1")
        assertThat(truth.marketClosePrice("EXNESS:XAUUSD", "XAUUSDm", Side.BUY)).isEqualByComparingTo("2400.3")
    }

    @Test
    fun `a tick with no price, or no tick at all, gives no close price`() {
        gateway.on("/symbol_info_tick/XAUUSDm", """{"bid":0,"ask":0,"time":1}""")

        assertThat(truth.marketClosePrice("EXNESS:XAUUSD", "XAUUSDm", Side.SELL)).isNull()
        assertThat(truth.marketClosePrice("EXNESS:EURUSD", "EURUSDm", Side.SELL)).isNull()
    }

    @Test
    fun `retries back off linearly`() {
        assertThat(truth.retryDelayMs(2)).isEqualTo(100L)
        assertThat(truth.retryDelayMs(4)).isEqualTo(300L)
    }
}
