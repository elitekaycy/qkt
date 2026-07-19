package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExitHookCompileTest {
    @Test
    fun `EXIT accessor outside a hook is rejected during compilation`() {
        assertThatThrownBy {
            compile(
                """
                STRATEGY bad VERSION 1
                SYMBOLS gold = TEST:XAUUSD EVERY 1m
                RULES WHEN EXIT.price > 0 THEN BUY gold SIZING 1
                """.trimIndent(),
            )
        }.hasMessageContaining("only valid inside ON_STOP")
    }

    @Test
    fun `compiled hook evaluates exit accessors relative price and GTD`() {
        val strategy =
            compile(
                """
                STRATEGY hook VERSION 1
                SYMBOLS gold = TEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 100 THEN BUY gold SIZING 2
                    ON_TP {
                      BUY gold SIZING EXIT.qty
                        ORDER_TYPE = LIMIT AGAINST 5
                        TIF GTD UNTIL NOW + 2h
                    }
                """.trimIndent(),
            )
        val hub = CandleHub()
        val key = strategy.declaredStreams.getValue("gold")
        hub.register(key, 2, "s")
        val signals = mutableListOf<Signal>()
        strategy.bindToHub(hub, testStrategyContext()) { signals.add(it) }
        hub.feed(Tick(key.qktSymbol, BigDecimal("101"), 0L))
        hub.feed(Tick(key.qktSymbol, BigDecimal("101"), 60_000L))
        val parent = signals.single() as Signal.Buy
        val ref = requireNotNull(parent.exitHook)

        val children =
            strategy.executeExitHook(
                ref,
                ExitContext(
                    price = BigDecimal("120"),
                    side = Side.SELL,
                    quantity = BigDecimal("2"),
                    pnl = BigDecimal("38"),
                    reason = ExitReason.TAKE_PROFIT,
                ),
                timestampMs = 60_000L,
            )
        val request = (children.single() as Signal.Submit).request as OrderRequest.Limit
        assertThat(request.quantity).isEqualByComparingTo("2")
        assertThat(request.limitPrice).isEqualByComparingTo("125")
        assertThat(request.expiresAt).isEqualTo(60_000L + 2L * 60 * 60 * 1000)
    }

    @Test
    fun `hook child cannot declare another exit hook`() {
        assertThatThrownBy {
            compile(
                """
                STRATEGY nested VERSION 1
                SYMBOLS gold = TEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 100 THEN BUY gold SIZING 1
                    ON_STOP {
                      SELL gold SIZING 1
                        ON_TP { BUY gold SIZING 1 }
                    }
                """.trimIndent(),
            )
        }.hasMessageContaining("cannot declare ON_FILL or nested ON_* hooks")
    }

    @Test
    fun `hook log evaluates string exit accessors`() {
        val strategy =
            compile(
                """
                STRATEGY hook_log VERSION 1
                SYMBOLS gold = TEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 100 THEN BUY gold SIZING 1
                    ON_CLOSE {
                      LOG "exit {exit_side} {exit_reason}"
                        exit_side = EXIT.side exit_reason = EXIT.reason
                    }
                """.trimIndent(),
            )
        val hub = CandleHub()
        val key = strategy.declaredStreams.getValue("gold")
        hub.register(key, 2, "s")
        val signals = mutableListOf<Signal>()
        strategy.bindToHub(hub, testStrategyContext()) { signals.add(it) }
        hub.feed(Tick(key.qktSymbol, BigDecimal("101"), 0L))
        hub.feed(Tick(key.qktSymbol, BigDecimal("101"), 60_000L))
        val ref = requireNotNull((signals.single() as Signal.Buy).exitHook)

        val children =
            strategy.executeExitHook(
                ref,
                ExitContext(
                    price = BigDecimal("99"),
                    side = Side.SELL,
                    quantity = BigDecimal.ONE,
                    pnl = BigDecimal("-2"),
                    reason = ExitReason.CLOSE,
                ),
                timestampMs = 60_000L,
            )

        assertThat(children).isEmpty()
    }

    @Test
    fun `hook fingerprint changes when a bound stream changes`() {
        val first =
            compile(
                """
                STRATEGY fingerprint VERSION 1
                SYMBOLS gold = TEST:XAUUSD EVERY 1m
                RULES WHEN gold.close > 0 THEN BUY gold SIZING 1
                  ON_CLOSE { BUY gold SIZING 1 }
                """.trimIndent(),
            )
        val second =
            compile(
                """
                STRATEGY fingerprint VERSION 1
                SYMBOLS gold = TEST:XAGUSD EVERY 1m
                RULES WHEN gold.close > 0 THEN BUY gold SIZING 1
                  ON_CLOSE { BUY gold SIZING 1 }
                """.trimIndent(),
            )

        val firstFingerprint =
            first
                .exitHookReferences()
                .values
                .single()
                .fingerprint
        val secondFingerprint =
            second
                .exitHookReferences()
                .values
                .single()
                .fingerprint

        assertThat(firstFingerprint).isNotEqualTo(secondFingerprint)
    }

    private fun compile(source: String): DslCompiledStrategy =
        when (val parsed = Dsl.parse(source)) {
            is ParseResult.Success -> AstCompiler().compile(parsed.value) as DslCompiledStrategy
            is ParseResult.Failure -> error(parsed.errors.joinToString { it.message })
        }
}
