package com.qkt.dsl.parse

import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Snapshot suffixes (`x@SELL`, `x@T-N`), bar offsets and `SINCE` windows: accepted forms and located errors. */
class SnapshotAndAggregateSyntaxTest {
    private fun source(cond: String) =
        "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\nLET x = b.close\nRULES\n    WHEN $cond THEN BUY b SIZING 1\n"

    private fun errors(cond: String): List<String> = (Dsl.parse(source(cond)) as ParseResult.Failure).errors.map { it.message }

    @Test
    fun `a SELL snapshot reads the LET captured at the last sell`() {
        val rule = (Dsl.parse(source("x@SELL > 0")) as ParseResult.Success).value.rules.single() as WhenThen

        assertThat((rule.cond as CmpOp).lhs).isEqualTo(Ref("x", SnapshotSell))
    }

    @Test
    fun `snapshot suffixes reject a fractional T-N and an unknown kind`() {
        assertThat(errors("x@T-1.5 > 0")).contains("expected positive integer after T-")
        assertThat(errors("x@NOW > 0")).anyMatch { it.startsWith("expected snapshot kind (buy/sell/open/T-N), got") }
    }

    @Test
    fun `a bar offset must be a non-negative integer`() {
        assertThat(errors("b.close[1.5] > 0")).contains("bar offset must be a non-negative integer: 1.5")
    }

    @Test
    fun `a max or min aggregate takes exactly one series`() {
        assertThat(errors("max(b.close, b.open) SINCE T-3 > 0")).contains("max aggregate expects exactly one series")

        val rule = (Dsl.parse(source("min(b.low) SINCE OPEN > 0")) as ParseResult.Success).value.rules.single() as WhenThen
        assertThat((rule.cond as CmpOp).lhs).isInstanceOf(Aggregate::class.java)
    }

    @Test
    fun `a SINCE window is OPEN or an integer T-N`() {
        assertThat(errors("max(b.close) SINCE T-2.5 > 0")).contains("expected positive integer after T-")
        assertThat(errors("max(b.close) SINCE NOW > 0")).anyMatch { it.startsWith("expected OPEN or T-N for window, got") }
    }
}
