package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** LATCH entries placed as stop orders, take-profit-only brackets, skipped entries and the RETEST_HOLD check. */
class LatchCompilerStopEntryTest {
    private fun num(v: String) = NumLit(BigDecimal(v))

    private fun latch(
        vararg entries: LatchEntry,
        confirm: com.qkt.dsl.ast.LatchConfirm = com.qkt.dsl.ast.LatchFirstTick,
    ) = Latch("gold", BreakOffset(null, num("0.5")), DurationAst(300_000L), null, entries.toList(), confirm)

    private val ec = LatchCompilerFixture.ctx(symbol = "XAUUSD")

    private fun build(
        entry: LatchEntry,
        direction: Int = 1,
    ): OrderRequest? =
        LatchCompilerFixture
            .compile(latch(entry))
            .entryBuilders
            .single()
            .build(direction, BigDecimal("2000"), ec)

    @Test
    fun `a STOP entry is placed beyond the anchor in the break direction with its expiry`() {
        val req =
            build(
                LatchEntry(LatchStop(DirRel(DirSense.WITH, num("3"))), expire = DurationAst(60_000L)),
            ) as OrderRequest.Stop

        assertThat(req.side).isEqualTo(Side.BUY)
        assertThat(req.stopPrice).isEqualByComparingTo("2003")
        assertThat(req.quantity).isEqualByComparingTo("1")
        assertThat(req.expiresAt).isEqualTo(60_000L)
    }

    @Test
    fun `a bracketed STOP entry on a short break takes its stop above and target below`() {
        val entry =
            LatchEntry(
                LatchStop(DirRel(DirSense.WITH, num("3"))),
                bracket = LatchBracket(DirRel(DirSense.AGAINST, num("10")), DirRel(DirSense.WITH, num("20"))),
                sizing = SizeQty(num("2")),
            )

        val req = build(entry, direction = -1) as OrderRequest.Bracket

        assertThat(req.side).isEqualTo(Side.SELL)
        assertThat((req.entry as OrderRequest.Stop).stopPrice).isEqualByComparingTo("1997")
        assertThat((req.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2010")
        assertThat(req.takeProfit).isEqualByComparingTo("1980")
        assertThat(req.quantity).isEqualByComparingTo("2")
    }

    @Test
    fun `a take-profit-only bracket parks the stop at the entry price`() {
        val targetOnly = LatchEntry(LatchMarket, bracket = LatchBracket(takeProfit = DirRel(DirSense.WITH, num("5"))))

        val req = build(targetOnly) as OrderRequest.Bracket

        assertThat(req.takeProfit).isEqualByComparingTo("2005")
        assertThat((req.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2000")
    }

    @Test
    fun `an entry whose stop sits on the wrong side of the entry is skipped`() {
        val inverted = LatchEntry(LatchMarket, bracket = LatchBracket(stopLoss = DirRel(DirSense.WITH, num("5"))))

        assertThat(build(inverted)).isNull()
    }

    @Test
    fun `an entry whose size is undefined is skipped`() {
        assertThat(build(LatchEntry(LatchMarket, sizing = SizeQty(StreamFieldRef("gold", "bid"))))).isNull()
    }

    @Test
    fun `an entry on an undeclared stream fails when the latch fires`() {
        assertThatThrownBy { build(LatchEntry(LatchMarket, stream = "copper")) }
            .hasMessageContaining("Unknown stream alias: copper")
    }

    @Test
    fun `a RETEST_HOLD distance must be a compile-time constant`() {
        val retest = LatchRetestHold(StreamFieldRef("gold", "close"), DurationAst(60_000L))

        assertThatThrownBy { LatchCompilerFixture.compile(latch(LatchEntry(LatchMarket), confirm = retest)) }
            .hasMessageContaining("LATCH RETEST_HOLD distance must be compile-time constant")
        assertThat(
            LatchCompilerFixture
                .compile(
                    latch(LatchEntry(LatchMarket), confirm = retest.copy(distance = num("2"))),
                ).confirm,
        ).isEqualTo(retest.copy(distance = num("2")))
    }
}
