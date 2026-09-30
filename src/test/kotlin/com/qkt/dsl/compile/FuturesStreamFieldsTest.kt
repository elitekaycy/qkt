package com.qkt.dsl.compile

import com.qkt.broker.continuous.ContinuousFixture
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `contract`, `dte`, `days_to_roll` on futures streams (BTCUSDT quarterlies, 8d@08:00 rolls), Undefined elsewhere. */
class FuturesStreamFieldsTest {
    private val now = Instant.parse("2024-09-20T00:00:00Z").toEpochMilli()
    private val gold =
        InstrumentMeta(
            "EXNESS:XAUUSD",
            BigDecimal("100"),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            null,
            BigDecimal("0.01"),
            2,
            0,
        )
    private val registry: InstrumentRegistry =
        LayeredInstrumentRegistry(
            listOf(
                ContinuousFixture().registry,
                object : InstrumentRegistry {
                    override fun lookup(qktSymbol: String) = gold.takeIf { qktSymbol == it.qktSymbol }
                },
            ),
        )
    private val streams =
        mapOf(
            "front" to HubKey("BINANCE_UM", "BTCUSDT@front", "15m"),
            "next" to HubKey("BINANCE_UM", "BTCUSDT@next", "15m"),
            "dec" to HubKey("BINANCE_UM", "BTCUSDT_241227", "15m"),
            "gold" to HubKey("EXNESS", "XAUUSD", "15m"),
        )

    private fun eval(
        stream: String,
        field: String,
        at: Long = now,
    ): Value {
        val candle =
            Candle("x", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, at - 1, at)
        val ctx =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = testStrategyContext(instruments = registry),
                evaluationTimeMs = at,
            )
        return ExprCompiler().compile(StreamFieldRef(stream, field)).evaluate(ctx)
    }

    private fun daysUntil(iso: String) =
        Value.Num(
            BigDecimal(
                Instant.parse(iso).toEpochMilli() - now,
            ).divide(BigDecimal(86_400_000), 6, RoundingMode.HALF_EVEN),
        )

    @Test
    fun `a front stream names its active contract and counts to its expiry and next roll`() {
        assertThat(eval("front", "contract")).isEqualTo(Value.Str("BTCUSDT_241227"))
        assertThat(eval("front", "dte")).isEqualTo(daysUntil("2024-12-27T08:00:00Z"))
        assertThat(eval("front", "days_to_roll")).isEqualTo(daysUntil("2024-12-19T08:00:00Z"))
    }

    @Test
    fun `a next stream follows the contract after the front one and rolls with it`() {
        assertThat(eval("next", "contract")).isEqualTo(Value.Str("BTCUSDT_250328"))
        assertThat(eval("next", "dte")).isEqualTo(daysUntil("2025-03-28T08:00:00Z"))
        assertThat(eval("next", "days_to_roll")).isEqualTo(daysUntil("2024-12-19T08:00:00Z"))
    }

    @Test
    fun `a listed contract counts both to its own expiry`() {
        assertThat(eval("dec", "contract")).isEqualTo(Value.Str("BTCUSDT_241227"))
        assertThat(eval("dec", "dte")).isEqualTo(daysUntil("2024-12-27T08:00:00Z"))
        assertThat(eval("dec", "days_to_roll")).isEqualTo(daysUntil("2024-12-27T08:00:00Z"))
    }

    @Test
    fun `a CFD stream has no contract`() {
        assertThat(listOf("contract", "dte", "days_to_roll").map { eval("gold", it) }).containsOnly(Value.Undefined)
    }

    @Test
    fun `tick value and multiplier read the instrument, for any instrument`() {
        assertThat(eval("gold", "tick_value")).isEqualTo(Value.Num(BigDecimal("1.00")))
        assertThat(eval("gold", "multiplier")).isEqualTo(Value.Num(BigDecimal("100")))
        assertThat(eval("front", "tick_value")).isEqualTo(Value.Num(BigDecimal("0.1")))
    }

    @Test
    fun `at the roll instant the stream already names the new contract`() {
        val roll = Instant.parse("2024-12-19T08:00:00Z").toEpochMilli()

        assertThat(eval("front", "contract", roll - 1)).isEqualTo(Value.Str("BTCUSDT_241227"))
        assertThat(eval("front", "days_to_roll", roll - 1)).isEqualTo(Value.Num(BigDecimal("0.000000")))
        assertThat(eval("front", "contract", roll)).isEqualTo(Value.Str("BTCUSDT_250328"))
    }

    @Test
    fun `at the end of the chain the last contract counts to its expiry, then there is none`() {
        val expiry = Instant.parse("2025-03-28T08:00:00Z").toEpochMilli()

        assertThat(eval("front", "days_to_roll", expiry - 86_400_000L)).isEqualTo(Value.Num(BigDecimal("1.000000")))
        assertThat(eval("front", "dte", expiry)).isEqualTo(Value.Undefined)
    }
}
