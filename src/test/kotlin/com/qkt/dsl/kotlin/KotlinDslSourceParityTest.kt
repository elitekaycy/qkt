package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The Kotlin builders produce the same rules the parser produces for the equivalent `.qkt` text. */
class KotlinDslSourceParityTest {
    private fun rulesOf(ast: StrategyAst) = ast.rules.map { (it as WhenThen).cond to it.action }

    private fun parsedRules(rules: String): List<Pair<Any, Any>> {
        val source = "STRATEGY k VERSION 1\nSYMBOLS\n    btc = BACKTEST:BTCUSDT EVERY 1h\nRULES\n$rules\n"
        return rulesOf((Dsl.parse(source) as ParseResult.Success).value)
    }

    private fun built(block: StrategyBuilder.(StreamRef) -> Unit): List<Pair<Any, Any>> =
        rulesOf(
            strategy("k", 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1h")
                block(btc)
            },
        )

    @Test
    fun `arithmetic, comparison and boolean builders match the parsed operators`() {
        val kotlin =
            built { btc ->
                rule {
                    whenever(
                        ((btc.close * 2.bd / 3L.bd) gte (-btc.open)) and
                            ((btc.high lte btc.low) or (btc.close neq btc.open)) and
                            not(sqrt(btc.close) gt log(btc.close)) and
                            (exp(btc.close) gt pow(btc.close, 2.bd)),
                    )
                    then { log("info", "px" to btc.close) }
                }
            }
        val parsed =
            parsedRules(
                "    WHEN btc.close * 2 / 3 >= -btc.open AND (btc.high <= btc.low OR btc.close != btc.open) AND " +
                    "NOT (sqrt(btc.close) > log(btc.close)) AND exp(btc.close) > pow(btc.close, 2)\n" +
                    "    THEN LOG \"info\" px=btc.close",
            )

        assertThat(kotlin).isEqualTo(parsed)
    }

    @Test
    fun `log levels and aggregate builders match the parsed actions`() {
        val kotlin =
            built { btc ->
                rule {
                    whenever(runMin(btc.low, sinceT(3)) lt runSum(btc.volume, sinceOpen))
                    then { error("err", "a" to btc.close) }
                }
                rule {
                    whenever(btc.close gt 0.bd)
                    then { debug("dbg") }
                }
            }
        val parsed =
            parsedRules(
                "    WHEN min(btc.low) SINCE T-3 < sum(btc.volume) SINCE OPEN THEN LOG ERROR \"err\" a=btc.close\n" +
                    "    WHEN btc.close > 0 THEN LOG DEBUG \"dbg\"",
            )

        assertThat(kotlin).isEqualTo(parsed)
    }

    @Test
    fun `indicator builders name the canonical indicator and pass the whole candle to atr`() {
        val btc = StreamRef("btc")

        assertThat(rsi(btc.close, 14)).isEqualTo(IndicatorCall("RSI", listOf(btc.close, 14.bd)))
        assertThat(atr(btc, 14)).isEqualTo(IndicatorCall("ATR", listOf(btc.candle, 14.bd)))
    }

    @Test
    fun `order type, sizing and bracket builders match the parsed entries`() {
        val kotlin =
            built { btc ->
                rule {
                    whenever(btc.close gt 0.bd)
                    then {
                        buy(
                            btc,
                            usdNotional(100.bd),
                            orderType = stopAt(btc.high),
                            bracket = bracket(childAt(btc.low), childPct(2.bd)),
                        )
                    }
                }
                rule {
                    whenever(btc.close gt 0.bd)
                    then { sell(btc, pctEquity("0.05".bd), orderType = stopLimit(btc.low, btc.low - 1.bd)) }
                }
                rule {
                    whenever(btc.close gt 0.bd)
                    then {
                        buy(
                            btc,
                            pctBalance("0.05".bd),
                            orderType = trailingBy(10.bd),
                            oco = oco(childAt(btc.low), childAt(btc.high)),
                        )
                    }
                }
                rule {
                    whenever(btc.close gt 0.bd)
                    then { sell(btc, 1.bd, orderType = trailingPct(2.bd)) }
                }
            }
        val parsed =
            parsedRules(
                "    WHEN btc.close > 0 THEN BUY btc SIZING 100 USD ORDER_TYPE = STOP AT btc.high " +
                    "BRACKET { STOP_LOSS AT btc.low, TAKE_PROFIT BY 2 PCT }\n" +
                    "    WHEN btc.close > 0 THEN SELL btc SIZING 5 PCT OF EQUITY " +
                    "ORDER_TYPE = STOP AT btc.low LIMIT AT btc.low - 1\n" +
                    "    WHEN btc.close > 0 THEN BUY btc SIZING 5 PCT OF BALANCE ORDER_TYPE = TRAILING BY 10 " +
                    "OCO { STOP AT btc.low, LIMIT AT btc.high }\n" +
                    "    WHEN btc.close > 0 THEN SELL btc SIZING 1 ORDER_TYPE = TRAILING PCT 2",
            )

        assertThat(kotlin).isEqualTo(parsed)
    }

    @Test
    fun `sizing-less entries carry only their order options`() {
        val kotlin =
            built { btc ->
                rule {
                    whenever(btc.close gt 0.bd)
                    then { sell(btc, orderType = market, tif = ioc) }
                }
                rule {
                    whenever(btc.close gt 0.bd)
                    then { buy(btc, tif = ioc) }
                }
            }
        val parsed =
            parsedRules(
                "    WHEN btc.close > 0 THEN SELL btc ORDER_TYPE = MARKET TIF IOC\n" +
                    "    WHEN btc.close > 0 THEN BUY btc TIF IOC",
            )

        assertThat(kotlin).isEqualTo(parsed)
    }

    @Test
    fun `a layer can carry its own order type and a sizing object`() {
        val l = layer(usdNotional(50.bd), orderType = limitAt(entryPrice - 5.bd), at = entryPrice - 5.bd)
        assertThat(layer(2.bd)).isEqualTo(
            StackLayer(
                com.qkt.dsl.ast
                    .SizeQty(2.bd),
            ),
        )

        val expected =
            StackLayer(
                com.qkt.dsl.ast
                    .SizeNotional(50.bd),
                com.qkt.dsl.ast
                    .Limit(entryPrice - 5.bd),
                entryPrice - 5.bd,
            )
        assertThat(l).isEqualTo(expected)
    }

    @Test
    fun `duration parses every unit and rejects malformed text`() {
        assertThat(duration("90s")).isEqualTo(DurationAst(90_000L))
        assertThat(duration("2d")).isEqualTo(DurationAst(172_800_000L))
        assertThatThrownBy { duration("5") }.hasMessageContaining("invalid duration '5'")
        assertThatThrownBy { duration("xm") }.hasMessageContaining("invalid duration 'xm'")
        assertThatThrownBy { duration("5w") }.hasMessageContaining("unknown duration unit 'w' in '5w'")
    }

    @Test
    fun `a rule without an action is rejected and defaults keep their order type and trailing`() {
        assertThatThrownBy {
            strategy("k", 1) { rule { whenever(1.bd gt 0.bd) } }
        }.hasMessageContaining("missing then")

        val ast =
            strategy("k", 1) {
                defaults {
                    orderType = market
                    trailing = trailingBy(3.bd)
                }
            }
        assertThat(ast.defaults?.orderType).isEqualTo(market)
        assertThat(ast.defaults?.trailing).isEqualTo(trailingBy(3.bd))
    }
}
