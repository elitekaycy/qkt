package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AstCompilerTest {
    @Test
    fun `compiled strategy emits BUY when fast greater than slow`() {
        val ast =
            StrategyAst(
                name = "ema_x",
                version = 1,
                streams = listOf(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m")),
                constants = emptyList(),
                lets =
                    listOf(
                        LetDecl(
                            "fast",
                            IndicatorCall(
                                "EMA",
                                listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))),
                            ),
                        ),
                        LetDecl(
                            "slow",
                            IndicatorCall(
                                "EMA",
                                listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("5"))),
                            ),
                        ),
                    ),
                defaults = null,
                rules =
                    listOf(
                        WhenThen(
                            cond = CmpOp(Cmp.GT, Ref("fast"), Ref("slow")),
                            action =
                                Buy(
                                    "btc",
                                    ActionOpts(
                                        sizing = SizeQty(NumLit(BigDecimal.ONE)),
                                        orderType = Market,
                                    ),
                                ),
                        ),
                    ),
            )
        val strategy = AstCompiler().compile(ast)

        val captured = mutableListOf<Signal>()
        for (price in listOf("100", "101", "102", "103", "104", "110", "120")) {
            val c =
                Candle(
                    "BTCUSDT",
                    BigDecimal(price),
                    BigDecimal(price),
                    BigDecimal(price),
                    BigDecimal(price),
                    BigDecimal.ZERO,
                    0L,
                    60_000L,
                )
            strategy.onCandle(c, testStrategyContext(), captured::add)
        }
        assertThat(captured).isNotEmpty
        assertThat(captured.first()).isInstanceOf(Signal.Buy::class.java)
    }

    @Test
    fun `unrelated symbol on candle does not fire rule`() {
        val ast =
            StrategyAst(
                name = "always_buy",
                version = 1,
                streams = listOf(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m")),
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules =
                    listOf(
                        WhenThen(
                            cond = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("0"))),
                            action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))),
                        ),
                    ),
            )
        val strategy = AstCompiler().compile(ast)
        val captured = mutableListOf<Signal>()
        val c =
            Candle(
                "OTHER",
                BigDecimal("100"),
                BigDecimal("100"),
                BigDecimal("100"),
                BigDecimal("100"),
                BigDecimal.ZERO,
                0L,
                60_000L,
            )
        strategy.onCandle(c, testStrategyContext(), captured::add)
        assertThat(captured).isEmpty()
    }
}
