package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Basket orders are plain market in v1, and fan out only once every constituent has a price. */
class BasketFanOutRefusalTest {
    private val aud = HubKey("EXNESS", "AUDUSD", "1h")
    private val nzd = HubKey("EXNESS", "NZDUSD", "1h")
    private val notional = SizeNotional(NumLit(BigDecimal("1000")))
    private val actions = ActionCompiler(ExprCompiler(), baskets = mapOf("pair" to listOf("aud", "nzd")))

    @Test
    fun `a basket entry cannot stack or rest as a limit`() {
        val stack = StackSpacing(2, NumLit(BigDecimal.ONE), StackDirection.ABOVE)

        assertThatThrownBy { actions.compile(Buy("pair", ActionOpts(sizing = notional, stack = stack))) }
            .hasMessageContaining("BASKET order on 'pair' cannot carry STACK/STACK_AT")
        assertThatThrownBy {
            actions.compile(Buy("pair", ActionOpts(sizing = notional, orderType = Limit(NumLit(BigDecimal.ONE)))))
        }.hasMessageContaining("BASKET order on 'pair' must be a market order")
    }

    @Test
    fun `a basket entry fails while a constituent has no bar yet`() {
        val hub = CandleHub()
        hub.register(aud, retention = 2, strategyId = "t")
        hub.register(nzd, retention = 2, strategyId = "t")
        val one = BigDecimal.ONE
        val candle = Candle(aud.qktSymbol, one, one, one, one, BigDecimal.ZERO, 0L, 1L)
        hub.publish(aud, candle)
        val ctx =
            EvalContext(
                candle = candle,
                streams = mapOf("aud" to aud, "nzd" to nzd, "pair" to HubKey("BASKET", "PAIR", "1h")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
                hub = hub,
            )

        assertThatThrownBy { actions.compile(Buy("pair", ActionOpts(sizing = notional)))(ctx) }
            .hasMessageContaining("BASKET 'pair' constituent 'nzd' has no price yet")
    }
}
