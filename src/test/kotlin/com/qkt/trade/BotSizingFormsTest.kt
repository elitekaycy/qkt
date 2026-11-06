package com.qkt.trade

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotSizingFormsTest : BotActionCompilerFixture() {
    private val price = BigDecimal("2000")

    private fun num(v: String) = NumLit(BigDecimal(v))

    @Test
    fun `usd notional divides by the value of one lot`() {
        // 20,000 USD / (2,000 x 100 oz) = 0.1 lots.
        assertThat(resolveBotLots(SizeNotional(num("20000")), ctx, price, null)).isEqualByComparingTo("0.10")
    }

    @Test
    fun `percent of balance sizes from the balance, not the equity`() {
        val rich = ctx.copy(balance = BigDecimal("2000000"), equity = BigDecimal("1"))
        // 2,000,000 x 0.01 / 200,000 = 0.1 lots.
        assertThat(resolveBotLots(SizePctBalance(num("0.01")), rich, price, null)).isEqualByComparingTo("0.10")
    }

    @Test
    fun `percent of balance refuses a venue that reports no balance`() {
        assertThatThrownBy { resolveBotLots(SizePctBalance(num("0.01")), ctx.copy(balance = null), price, null) }
            .hasMessage("venue did not report account balance; pass explicit lots instead")
    }

    @Test
    fun `absolute risk divides by the value of the stop distance`() {
        // 300 USD / (30 x 100 oz) = 0.1 lots.
        assertThat(resolveBotLots(SizeRiskAbs(num("300")), ctx, price, BigDecimal("30"))).isEqualByComparingTo("0.10")
    }

    @Test
    fun `a sizing form that needs a position is not supported one-shot`() {
        assertThatThrownBy { resolveBotLots(SizePositionFull("x"), ctx, price, null) }
            .hasMessageStartingWith("sizing form SizePositionFull is not supported one-shot")
    }

    @Test
    fun `a missing sizing clause or a non literal size is refused`() {
        assertThatThrownBy { resolveBotLots(null, ctx, price, null) }
            .hasMessage("bot order requires a SIZING clause")
        assertThatThrownBy { resolveBotLots(SizeQty(Ref("close")), ctx, price, null) }
            .hasMessage("SIZING must be a numeric literal one-shot, got Ref")
    }

    @Test
    fun `money sizing refuses a venue that reports no contract size`() {
        assertThatThrownBy { resolveBotLots(SizeNotional(num("20000")), ctx.copy(contractSize = null), price, null) }
            .hasMessage("venue did not report a contract size; pass explicit lots instead")
    }

    @Test
    fun `quantizing refuses a volume above the instrument maximum`() {
        assertThatThrownBy { quantizeLots(BigDecimal("51"), ctx) }
            .hasMessage("volume 51.00 exceeds the instrument maximum 50")
    }

    @Test
    fun `quantizing without venue limits keeps the raw volume but refuses zero`() {
        val open = ctx.copy(volumeMin = null, volumeStep = null, volumeMax = null)
        assertThat(quantizeLots(BigDecimal("0.123"), open)).isEqualByComparingTo("0.123")
        assertThatThrownBy { quantizeLots(BigDecimal.ZERO, open) }
            .hasMessage("resolved volume must be > 0: 0")
    }
}
