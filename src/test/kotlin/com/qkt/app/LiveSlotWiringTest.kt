package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.marketdata.source.LiveSlotMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A live session serves the event slots and event chain streams its strategies declare; other sessions are untouched. */
class LiveSlotWiringTest {
    private val source =
        object : MarketSource {
            override val name = "gateway"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true
        }

    private fun strategy(streams: String) =
        "s" to
            AstCompiler().compile(
                (
                    Dsl.parse("STRATEGY t VERSION 1\nSYMBOLS\n$streams\nRULES\n    WHEN g.close > 0 THEN LOG \"x\"\n")
                        as ParseResult.Success
                ).value,
            )

    @Test
    fun `slots and event chain streams are served over the session's source, on their shortest window`() {
        val strategies =
            listOf(
                strategy(
                    "    p = KALSHI:KXS@front.p70 EVERY 5m,\n    q = KALSHI:KXS@front.p70 EVERY 1m,\n" +
                        "    m = CHAIN:KALSHI.KXS.overround.front EVERY 1m,\n    g = EXNESS:XAUUSD EVERY 1m",
                ),
            )

        val served = liveSlotSource(source, strategies, SlotFamilyFixture.registry())

        assertThat(served).isInstanceOf(LiveSlotMarketSource::class.java)
        assertThat(served.supports("KALSHI:KXS@front.p70")).isTrue()
        assertThat(served.capabilitiesFor("CHAIN:KALSHI.KXS.overround.front"))
            .containsExactly(MarketSourceCapability.LIVE_TICKS)
    }

    @Test
    fun `a session without event streams keeps its source, and one with them needs its registry`() {
        val plain = listOf(strategy("    g = EXNESS:XAUUSD EVERY 1m"))
        val slotted = listOf(strategy("    p = KALSHI:KXS@front.atm EVERY 1m,\n    g = EXNESS:XAUUSD EVERY 1m"))

        assertThat(liveSlotSource(source, plain, null)).isSameAs(source)
        assertThatThrownBy { liveSlotSource(source, slotted, null) }
            .hasMessageContaining("[KALSHI:KXS@front.atm] need the session's instrument registry")
    }

    @Test
    fun `a slot passes the live start-up checks and is not judged for price outliers`() {
        assertThatCode { requireLiveTradable(listOf("KALSHI:KXS@front.p70"), AccountingEngine()) }
            .doesNotThrowAnyException()
        assertThat(judgesPriceOutliers(SlotFamilyFixture.registry(), "KALSHI:KXS@front.p70")).isFalse()
    }
}
