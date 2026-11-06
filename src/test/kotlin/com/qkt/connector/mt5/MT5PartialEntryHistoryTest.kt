package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.mt5.MT5PartialEntryHistory.Replay
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.deal
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Part-filled entry 0.04 of 0.10 on position 9001, read back from its deals. */
class MT5PartialEntryHistoryTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = 1_800_000_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val partials = MT5PartialEntries(books, bus, clock)
    private val history =
        MT5PartialEntryHistory(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            clock,
            partials,
        )
    private val filled = mutableListOf<BrokerEvent.OrderFilled>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { filled.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun registerPartial() {
        partials.registerPartialEntry(
            PartialEntryState(
                meta = MT5TicketMeta("entry-1", "gold"),
                residualTicket = 9002L,
                positionTicket = 9001L,
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                requestedQuantity = BigDecimal("0.10"),
                cumulativeFilled = BigDecimal("0.04"),
                averageFillPrice = BigDecimal("2400"),
            ),
            openedAtMs = 1_000L,
        )
    }

    private fun fullRoundTrip() =
        gateway.on(
            "/history_deals_get",
            listOf(
                deal(1, 9001, entry = 0, volume = "0.04", price = "2400", timeMs = 2_000L),
                deal(2, 9001, entry = 0, volume = "0.06", price = "2405", timeMs = 3_000L),
                deal(3, 9001, entry = 1, volume = "0.10", price = "2410", type = 1, timeMs = 4_000L),
                deal(4, 9001, entry = 0, volume = "0.01", price = "0", timeMs = 1_500L),
            ).joinToString(",", "[", "]"),
        )

    @Test
    fun `a ticket without a part-filled entry is not read from history`() {
        assertThat(history.replay(9001L, BigDecimal.ZERO)).isEqualTo(Replay.NOT_PARTIAL)
        assertThat(gateway.requests).isEmpty()
    }

    @Test
    fun `deals showing the rest filled and the position closed complete the entry`() {
        registerPartial()
        fullRoundTrip()

        assertThat(history.replay(9001L, BigDecimal.ZERO)).isEqualTo(Replay.REPLAYED)

        val fill = filled.single()
        assertThat(fill.quantity).isEqualByComparingTo("0.06")
        assertThat(fill.price).isEqualByComparingTo("2405")
        assertThat(partials.isPartialEntry(9001L)).isFalse()
        assertThat(books.positionBook.openedAt(9001L)).isEqualTo(2_000L)
        assertThat(gateway.calls("/history_deals_get").single()).contains("position=9001")
    }

    @Test
    fun `history that disagrees with the venue's open volume is asked again`() {
        registerPartial()
        fullRoundTrip()

        assertThat(history.replay(9001L, BigDecimal("0.04"))).isEqualTo(Replay.UNKNOWN)
        assertThat(filled).isEmpty()
    }

    @Test
    fun `five unusable answers in a row fall back to the snapshot, then the count starts over`() {
        registerPartial()
        gateway.fail("/history_deals_get")

        val answers = (1..6).map { history.replay(9001L, BigDecimal("0.04")) }

        assertThat(answers).containsExactly(
            Replay.UNKNOWN,
            Replay.UNKNOWN,
            Replay.UNKNOWN,
            Replay.UNKNOWN,
            Replay.NOT_PARTIAL,
            Replay.UNKNOWN,
        )
    }

    @Test
    fun `history with no priced opening deal is unusable`() {
        registerPartial()
        gateway.on("/history_deals_get", "[" + deal(4, 9001, entry = 0, volume = "0.04", price = "0") + "]")

        assertThat(history.replay(9001L, BigDecimal.ZERO)).isEqualTo(Replay.UNKNOWN)
    }

    @Test
    fun `growth booked from history is handed to the poller once`() {
        registerPartial()
        fullRoundTrip()

        assertThat(history.growthBeside(9001L, BigDecimal.ZERO)).isEqualByComparingTo("0.06")
        assertThat(history.growthBeside(9001L, null)).isEqualByComparingTo("0")
    }

    @Test
    fun `growth is withheld while history cannot be trusted`() {
        registerPartial()
        gateway.fail("/history_deals_get")

        assertThat(history.growthBeside(9001L, BigDecimal("0.04"))).isNull()
    }
}
