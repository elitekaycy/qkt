package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.quote
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.instrument.TradeMode
import com.qkt.marketdata.Candle
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.QuoteMapSource.Companion.at
import com.qkt.marketdata.source.QuoteMapSource.Companion.range
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A live session's slot and event chain stream over the `KXS` family's quotes as a gateway feeds them (every contract
 * under its own symbol): the slot picks as a backtest picks, the stream values as a backtest values, every family
 * tick passes through, and a contract the venue paused is never picked.
 */
class LiveSlotMarketSourceTest {
    private val slot = "KALSHI:KXS@front.p70"
    private val chain = "CHAIN:KALSHI.KXS.implied_median.front"

    private fun ladder(
        event: Int,
        hhmm: String,
        vararg mids: String,
    ): List<Tick> =
        mids.mapIndexed { i, mid ->
            val m = mid.toBigDecimal()
            val cent = "0.01".toBigDecimal()
            quote(symbol(event, i + 1), at(hhmm), (m - cent).toPlainString(), (m + cent).toPlainString())
        }

    private val quotes =
        ladder(1, "10:00", "0.90", "0.70", "0.40", "0.10") +
            ladder(1, "10:01", "0.90", "0.70", "0.40", "0.10") +
            Tick("EXNESS:XAUUSD", BigDecimal("2650"), at("10:01")) +
            ladder(1, "10:02", "0.80", "0.50", "0.40", "0.10") +
            ladder(1, "10:03", "0.80", "0.50", "0.40", "0.10") +
            ladder(2, "10:30", "0.95", "0.80", "0.68", "0.20") +
            ladder(2, "11:00", "0.95", "0.80", "0.68", "0.20") +
            ladder(2, "11:01", "0.95", "0.80", "0.68", "0.20")

    private class Inner(
        private val ticks: List<Tick>,
    ) : MarketSource {
        val asked = mutableListOf<List<String>>()
        override val name: String = "gateway"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = symbol.startsWith("KALSHI:")

        override fun tickDeliveryLagMsFor(symbol: String): Long = 7L

        override fun liveTicks(symbols: List<String>): TickFeed {
            asked += symbols
            return HistoricalTickFeed(ticks)
        }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> = sequenceOf(Candle(symbol, ONE, ONE, ONE, ONE, ONE, 0L, 60_000L))

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> = sequenceOf(Tick(symbol, ONE, 0L))

        override fun slotContractAt(
            symbol: String,
            atMs: Long,
        ): String = "inner"
    }

    private fun drain(feed: TickFeed): List<Tick> = generateSequence { feed.next() }.toList()

    @Test
    fun `a live slot follows the contract a backtest would, and every family tick still passes`() {
        val inner = Inner(quotes)
        val source = LiveSlotMarketSource(inner, SlotFamilyFixture.registry(), mapOf(slot to 60_000L))
        val backtest = SlotMarketSource(QuoteMapSource(quotes), SlotFamilyFixture.registry(), listOf(slot))

        val ticks = drain(source.liveTicks(listOf(slot, "EXNESS:XAUUSD")))

        assertThat(inner.asked.single()).containsExactly(slot, "EXNESS:XAUUSD")
        assertThat(ticks.filter { it.symbol != slot }).containsExactlyElementsOf(quotes)
        val expected =
            backtest.sampledTicks(slot, TimeWindow.parse("1m"), range()).filter { it.symbol == slot }.toList()
        assertThat(ticks.filter { it.symbol == slot }).containsExactlyElementsOf(expected)
        assertThat(source.slotContractAt(slot, at("10:02"))).isEqualTo(symbol(1, 2))
        assertThat(source.slotContractAt(slot, at("10:04"))).isEqualTo(symbol(1, 1))
        assertThat(source.slotContractAt(slot, at("10:01"))).isNull()
        assertThat(source.slotContractAt("KALSHI:OTHER@front.atm", at("10:04"))).isEqualTo("inner")
    }

    @Test
    fun `a contract the venue paused is not picked`() {
        val base = SlotFamilyFixture.registry()
        val paused =
            object : InstrumentRegistry by base {
                override fun lookup(qktSymbol: String): InstrumentMeta? =
                    base.lookup(qktSymbol)?.let {
                        if (qktSymbol == symbol(1, 2)) it.copy(tradeMode = TradeMode.DISABLED) else it
                    }
            }
        val source = LiveSlotMarketSource(Inner(quotes), paused, mapOf(slot to 60_000L))

        drain(source.liveTicks(listOf(slot)))

        assertThat(source.slotContractAt(slot, at("10:02"))).isEqualTo(symbol(1, 1))
    }

    @Test
    fun `a live event chain stream ends each instant on the value a backtest gives it`() {
        val source = LiveSlotMarketSource(Inner(quotes), SlotFamilyFixture.registry(), mapOf(chain to 60_000L))
        val backtest = EventChainMarketSource(QuoteMapSource(quotes), SlotFamilyFixture.registry(), listOf(chain))

        val live = drain(source.liveTicks(listOf(chain))).filter { it.symbol == chain }
        val expected = backtest.ticks(chain, range()).associate { it.timestamp to it.price }

        assertThat(expected).isNotEmpty
        val lastPerInstant = live.groupBy { it.timestamp }.mapValues { (_, t) -> t.last().price }
        assertThat(lastPerInstant.filterKeys { it in expected }).isEqualTo(expected)
    }

    @Test
    fun `slots and chain streams have no bars, marks or tape of their own, and other symbols are the inner source's`() {
        val inner = Inner(quotes)
        val source = LiveSlotMarketSource(inner, SlotFamilyFixture.registry(), mapOf(slot to 60_000L))
        val window = TimeWindow.parse("1m")

        assertThat(source.name).isEqualTo("gateway")
        assertThat(source.capabilities).containsExactly(MarketSourceCapability.LIVE_TICKS)
        assertThat(source.supports(slot)).isTrue()
        assertThat(source.supports("EXNESS:XAUUSD")).isFalse()
        assertThat(source.capabilitiesFor(slot)).containsExactly(MarketSourceCapability.LIVE_TICKS)
        assertThat(source.capabilitiesFor(symbol(1, 1))).containsExactly(MarketSourceCapability.LIVE_TICKS)
        assertThat(source.bars(slot, window, range()).toList()).isEmpty()
        assertThat(source.bars(symbol(1, 1), window, range()).toList()).hasSize(1)
        assertThat(source.ticks(slot, range()).toList()).isEmpty()
        assertThat(source.ticks(symbol(1, 1), range()).toList()).hasSize(1)
        assertThat(source.marksFor(slot)).isNull()
        assertThat(source.marksFor(symbol(1, 1))).isNull()
        assertThat(source.optionMarksFor(slot)).isNull()
        assertThat(source.optionMarksFor(symbol(1, 1))).isNull()
        assertThat(source.tradeFlowFor(slot)).isNull()
        assertThat(source.tradeFlowFor(symbol(1, 1))).isNull()
        assertThat(source.tickDeliveryLagMsFor(slot)).isEqualTo(7L)
        assertThat(drain(source.liveTicks(listOf(symbol(1, 1))))).containsExactlyElementsOf(quotes)
    }

    private companion object {
        val ONE: BigDecimal = BigDecimal.ONE
    }
}
