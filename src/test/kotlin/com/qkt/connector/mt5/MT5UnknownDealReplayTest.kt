package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.deal
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** An unanswered placement settled from deal history: E.g. a market buy that filled and was stopped out unseen. */
class MT5UnknownDealReplayTest {
    private val sentAt = 1_800_000_000_000L
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = sentAt + 5_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val accepted = mutableListOf<BrokerEvent.OrderAccepted>()
    private val filled = mutableListOf<BrokerEvent.OrderFilled>()
    private val replay =
        MT5UnknownDealReplay(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            bus,
            clock,
            books,
        )
    private val request =
        OrderRequest.Market(
            id = "dsl-gold--4",
            symbol = "EXNESS:XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = sentAt,
            strategyId = "gold",
        )
    private val placement =
        MT5OrderRequest(
            symbol = "XAUUSDm",
            volume = BigDecimal("0.10"),
            type = "BUY",
            magic = 10001,
            comment = "dsl-gold--4",
            clientOrderId = "cid-1",
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { accepted.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { filled.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun deals(vararg rows: String) = gateway.on("/history_deals_get", rows.joinToString(",", "[", "]"))

    private fun resolve(positions: List<MT5Position> = emptyList()) =
        replay.resolveFromDealHistory(request, placement, sentAt, null, "XAUUSDm", "dsl-gold--4", positions, 1)

    private fun openPosition(ticket: Long) =
        MT5Position(
            ticket = ticket,
            symbol = "XAUUSDm",
            type = 0,
            volume = BigDecimal("0.10"),
            priceOpen = BigDecimal("2400"),
            sl = BigDecimal.ZERO,
            tp = BigDecimal.ZERO,
            profit = BigDecimal.ZERO,
            magic = 10001,
            openTime = sentAt,
        )

    @Test
    fun `an unreadable deal history proves nothing either way`() {
        gateway.fail("/history_deals_get")

        assertThat(resolve()).isEqualTo(MT5UnknownDealReplay.Outcome.INCONCLUSIVE)
        assertThat(filled).isEmpty()
    }

    @Test
    fun `deals that cannot be this order are a clean absence`() {
        books.positionBook.track(905L, MT5TicketMeta("other", "s"), "EXNESS:XAUUSD", 0L)
        deals(
            deal(1, 901, entry = 1, volume = "0.10", price = "2400", clientOrderId = "cid-1"),
            deal(2, 902, entry = 0, volume = "0.10", price = "2400", magic = 7, clientOrderId = "cid-1"),
            deal(3, 903, entry = 0, volume = "0.10", price = "2400", symbol = "EURUSDm", clientOrderId = "cid-1"),
            deal(4, 904, entry = 0, volume = "0.10", price = "2400", comment = "someone-else"),
            deal(5, 905, entry = 0, volume = "0.10", price = "2400", clientOrderId = "cid-1"),
        )

        assertThat(resolve()).isEqualTo(MT5UnknownDealReplay.Outcome.CLEAN_ABSENCE)
        assertThat(accepted).isEmpty()
    }

    @Test
    fun `an opening deal carrying the placement id on a still open position is booked as the fill`() {
        deals(deal(11, 901, entry = 0, volume = "0.10", price = "2400.50", clientOrderId = "cid-1", timeMs = sentAt))

        assertThat(resolve(listOf(openPosition(901)))).isEqualTo(MT5UnknownDealReplay.Outcome.RESOLVED)

        assertThat(accepted.single().brokerOrderId).isEqualTo("901")
        val fill = filled.single()
        assertThat(fill.clientOrderId).isEqualTo("dsl-gold--4")
        assertThat(fill.price).isEqualByComparingTo("2400.50")
        assertThat(fill.quantity).isEqualByComparingTo("0.10")
        assertThat(books.positionBook.meta(901L)?.orderId).isEqualTo("dsl-gold--4")
        assertThat(books.positionBook.openedAt(901L)).isEqualTo(sentAt)
    }

    @Test
    fun `a position opened and stopped out unseen replays its entry and its exit with venue costs`() {
        deals(
            deal(11, 901, entry = 0, volume = "0.10", price = "2400", comment = "dsl-gold--4", timeMs = sentAt + 1),
            deal(13, 901, entry = 1, volume = "0.04", price = "2395", type = 1, timeMs = sentAt + 3, reason = 4),
            deal(
                12,
                901,
                entry = 1,
                volume = "0.06",
                price = "2396",
                type = 1,
                timeMs = sentAt + 2,
                commission = "-0.70",
                reason = 5,
            ),
        )

        assertThat(resolve()).isEqualTo(MT5UnknownDealReplay.Outcome.RESOLVED)

        assertThat(filled).hasSize(3)
        assertThat(filled[0].updatesOrderExecution).isTrue()
        val (first, last) = filled[1] to filled[2]
        assertThat(first.price).isEqualByComparingTo("2396")
        assertThat(first.side).isEqualTo(Side.SELL)
        assertThat(first.exitReason).isEqualTo(ExitReason.TAKE_PROFIT)
        assertThat(first.venueCosts).isEqualByComparingTo("0")
        assertThat(last.price).isEqualByComparingTo("2395")
        assertThat(last.exitReason).isEqualTo(ExitReason.STOP)
        assertThat(last.venueCosts).isEqualByComparingTo("0.70")
        assertThat(last.updatesOrderExecution).isFalse()
        assertThat(books.positionBook.isAttributed(901L)).isFalse()
    }

    @Test
    fun `two positions carrying the same placement id are ambiguous`() {
        deals(
            deal(11, 901, entry = 0, volume = "0.10", price = "2400", clientOrderId = "cid-1"),
            deal(12, 902, entry = 0, volume = "0.10", price = "2400", clientOrderId = "cid-1"),
        )

        assertThat(resolve()).isEqualTo(MT5UnknownDealReplay.Outcome.INCONCLUSIVE)
        assertThat(filled).isEmpty()
    }

    @Test
    fun `a comment match of the wrong size is ambiguous rather than absent`() {
        deals(deal(11, 901, entry = 0, volume = "0.05", price = "2400", comment = "dsl-gold--4", timeMs = sentAt))

        assertThat(resolve()).isEqualTo(MT5UnknownDealReplay.Outcome.INCONCLUSIVE)
    }

    @Test
    fun `a flat position whose exits do not cover its entry waits for more history`() {
        deals(
            deal(11, 901, entry = 0, volume = "0.10", price = "2400", clientOrderId = "cid-1"),
            deal(12, 901, entry = 1, volume = "0.04", price = "2395", type = 1),
        )

        assertThat(resolve()).isEqualTo(MT5UnknownDealReplay.Outcome.INCONCLUSIVE)
        assertThat(accepted).isEmpty()
    }

    @Test
    fun `an opening deal with no ticket at all cannot be booked`() {
        deals(deal(0, 0, entry = 0, volume = "0.10", price = "2400", clientOrderId = "cid-1"))

        assertThat(resolve()).isEqualTo(MT5UnknownDealReplay.Outcome.INCONCLUSIVE)
    }

    @Test
    fun `an opening deal of zero size cannot be booked`() {
        deals(deal(11, 901, entry = 0, volume = "0", price = "2400", clientOrderId = "cid-1"))

        assertThat(resolve(listOf(openPosition(901)))).isEqualTo(MT5UnknownDealReplay.Outcome.INCONCLUSIVE)
        assertThat(filled).isEmpty()
    }
}
