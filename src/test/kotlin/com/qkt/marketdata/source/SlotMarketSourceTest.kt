package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.quote
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.QuoteMapSource.Companion.at
import com.qkt.marketdata.source.QuoteMapSource.Companion.range
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A `@front.p70` slot over the `KXS` family: H1's ladder mids are 0.90/0.70/0.40/0.10 from 10:00, then at 10:02 the
 * 100 and 200 strikes fall to 0.80 and 0.50, so from the 10:03 window the slot follows the 100 strike. After H1 stops
 * trading at 11:00 the slot follows H2, whose 300 strike (0.68) is nearest 0.70.
 */
class SlotMarketSourceTest {
    private val slot = "KALSHI:KXS@front.p70"
    private val registry = SlotFamilyFixture.registry()

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
            ladder(1, "10:02", "0.80", "0.50", "0.40", "0.10") +
            ladder(1, "10:03", "0.80", "0.50", "0.40", "0.10") +
            ladder(2, "10:30", "0.95", "0.80", "0.68", "0.20") +
            ladder(2, "11:00", "0.95", "0.80", "0.68", "0.20") +
            ladder(2, "11:01", "0.95", "0.80", "0.68", "0.20")

    private fun source(declared: List<String> = listOf(slot)) =
        SlotMarketSource(QuoteMapSource(quotes), registry, declared)

    @Test
    fun `the slot repeats the followed contract's ticks and re-picks at each window close`() {
        val source = source()
        val ticks = source.sampledTicks(slot, TimeWindow.parse("1m"), range()).toList()
        val slotTicks = ticks.filter { it.symbol == slot }

        // No pick before the first quotes; 10:01 and 10:02 follow the 200 strike, 10:03 the 100 strike.
        assertThat(slotTicks.map { it.timestamp to it.ask?.toPlainString() }).containsExactly(
            at("10:01") to "0.71",
            at("10:02") to "0.51",
            at("10:03") to "0.81",
            at("11:00") to "0.69",
            at("11:01") to "0.69",
        )
        assertThat(source.slotContractAt(slot, at("10:02"))).isEqualTo(symbol(1, 2))
        assertThat(source.slotContractAt(slot, at("10:04"))).isEqualTo(symbol(1, 1))
        assertThat(source.slotContractAt(slot, at("11:01"))).isEqualTo(symbol(2, 3))
        assertThat(source.slotContractAt(slot, at("10:01"))).isNull()
        assertThat(source.slotContractAt(slot, at("09:00"))).isNull()
        // The first slot of the family carries the contract ticks too.
        assertThat(ticks.count { it.symbol != slot }).isEqualTo(quotes.size)
    }

    @Test
    fun `a window names the cadence, and none re-picks each minute`() {
        val hourly = source().sampledTicks(slot, TimeWindow.parse("1h"), range()).filter { it.symbol == slot }.toList()
        val minutely = source().ticks(slot, range()).filter { it.symbol == slot }.toList()

        // Hourly: no quotes before 10:00, so nothing is picked for the 10:00 hour; 11:00 picks H2's 300 strike.
        assertThat(hourly.map { it.timestamp }).containsExactly(at("11:00"), at("11:01"))
        assertThat(minutely).hasSize(5)
    }

    @Test
    fun `a second slot of the family and explicit contracts do not repeat the contract ticks`() {
        val atm = "KALSHI:KXS@front.atm"
        val explicit = symbol(1, 1)
        val source = source(listOf(slot, atm, explicit))

        val first = source.ticks(slot, range()).filterNot { it.symbol == slot }.toList()
        val second = source.ticks(atm, range()).filterNot { it.symbol == atm }.toList()

        assertThat(first.map { it.symbol }).doesNotContain(explicit).isNotEmpty()
        assertThat(second).isEmpty()
    }

    @Test
    fun `any other symbol is served by the inner source`() {
        val source = source()
        val other = symbol(1, 1)

        assertThat(source.name).isEqualTo("quotes")
        assertThat(source.capabilities).containsExactly(MarketSourceCapability.TICKS)
        assertThat(source.supports(slot)).isTrue()
        assertThat(source.supports(other)).isTrue()
        assertThat(source.supports("KALSHI:NONE")).isFalse()
        assertThat(source.capabilitiesFor(slot)).containsExactly(MarketSourceCapability.TICKS)
        assertThat(source.capabilitiesFor(other)).containsExactly(MarketSourceCapability.TICKS)
        assertThat(source.tickDeliveryLagMsFor(slot)).isZero()
        assertThat(source.marksFor(slot)).isNull()
        assertThat(source.marksFor(other)).isNull()
        assertThat(source.optionMarksFor(slot)).isNull()
        assertThat(source.optionMarksFor(other)).isNull()
        assertThat(source.tradeFlowFor(slot)).isNull()
        assertThat(source.tradeFlowFor(other)).isNull()
        assertThat(source.ticks(other, range()).toList()).hasSize(4)
        assertThat(source.sampledTicks(other, null, range()).toList()).hasSize(4)
        assertThat(source.tickSlice(other, at("10:00"), at("10:02")).toList()).hasSize(2)
        assertThat(source.slotContractAt(other, at("10:02"))).isNull()
        assertThat(source.bars(slot, TimeWindow.parse("1m"), range()).toList()).isEmpty()
        assertThatThrownBy { source.bars(other, TimeWindow.parse("1m"), range()) }
            .isInstanceOf(UnsupportedDataException::class.java)
        assertThatThrownBy { source.liveTicks(listOf(other)) }.isInstanceOf(UnsupportedDataException::class.java)
    }

    @Test
    fun `slots refuse live feeds, tick slices and undeclared families`() {
        assertThatThrownBy { source().liveTicks(listOf(slot)) }.hasMessageContaining("backtests only")
        assertThatThrownBy { source().tickSlice(slot, 0L, 1L) }.hasMessageContaining("--tick-fills")
        assertThatThrownBy { SlotMarketSource(QuoteMapSource(quotes), NoopInstrumentRegistry, listOf(slot)) }
            .hasMessageContaining("KALSHI:KXS is not an event family declared under events:")
    }
}
