package com.qkt.dsl.compile

import com.qkt.app.requireEventTerms
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.ProbabilityFee
import com.qkt.instrument.StrikePredicate
import com.qkt.instrument.StrikeType
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Event-contract fields read the stream instrument's terms at evaluation time (the bar's close): a ladder contract
 * with a floor, a fee and an occasion; a between contract with both bounds and no fee; a custom one with neither.
 */
class EventStreamFieldsTest {
    private val ladder = EventContracts.SYMBOL
    private val between = "KALSHI:KXHIGHNY_26NOV06_B73.5"
    private val custom = "KALSHI:KXNBAGAME_26NOV06BOSNYK_BOS"
    private val base = EventContracts.terms()
    private val closeAtMs = base.closeAtMs
    private val occursAtMs = closeAtMs - 3_600_000L
    private val fee = ProbabilityFee(BigDecimal("0.07"), BigDecimal("0.0175"), BigDecimal("0.01"))
    private val cent = BigDecimal("0.01")
    private val gold = InstrumentMeta("EXNESS:XAUUSD", BigDecimal("100"), cent, cent, null, cent, 2, 0)
    private val registry =
        EventContracts.registry(
            EventContracts.meta(terms = base.copy(occursAtMs = occursAtMs, title = "7,425 or above", fee = fee)),
            EventContracts.meta(
                between,
                base.copy(predicate = StrikePredicate(StrikeType.BETWEEN, BigDecimal("73"), BigDecimal("74"))),
            ),
            EventContracts.meta(custom, base.copy(predicate = StrikePredicate(StrikeType.CUSTOM, null, null))),
            gold,
        )
    private val streams =
        mapOf("k" to ladder, "b" to between, "c" to custom, "g" to "EXNESS:XAUUSD").mapValues { (_, s) ->
            HubKey(s.substringBefore(':'), s.substringAfter(':'), "1m")
        }

    private fun eval(
        alias: String,
        field: String,
        at: Long = closeAtMs - 90 * 60_000L - 30_000L,
        bid: String? = "0.40",
        ask: String? = "0.50",
        instruments: InstrumentRegistry = registry,
        compiler: (StreamFieldRef) -> CompiledExpr = { ExprCompiler().compile(it) },
    ): Value {
        val one = BigDecimal.ONE
        val symbol = streams.getValue(alias).qktSymbol
        val candle = Candle(symbol, one, one, one, one, one, at - 60_000L, at, bid?.toBigDecimal(), ask?.toBigDecimal())
        val ctx =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = testStrategyContext(instruments = instruments),
                evaluationTimeMs = at,
            )
        return compiler(StreamFieldRef(alias, field)).evaluate(ctx)
    }

    private fun num(value: Value): BigDecimal = (value as Value.Num).v

    @Test
    fun `strike bounds read the predicate, Undefined where the contract has none`() {
        assertThat(num(eval("k", "floor_strike"))).isEqualByComparingTo("7425")
        assertThat(eval("k", "cap_strike")).isEqualTo(Value.Undefined)
        assertThat(num(eval("b", "floor_strike"))).isEqualByComparingTo("73")
        assertThat(num(eval("b", "cap_strike"))).isEqualByComparingTo("74")
        assertThat(listOf("floor_strike", "cap_strike").map { eval("c", it) }).containsOnly(Value.Undefined)
    }

    @Test
    fun `times are epoch ms and minutes count from the bar's close`() {
        assertThat(num(eval("k", "close_at"))).isEqualByComparingTo(closeAtMs.toBigDecimal())
        assertThat(num(eval("k", "expiry_at"))).isEqualByComparingTo(base.expiryMs.toBigDecimal())
        assertThat(num(eval("k", "occurs_at"))).isEqualByComparingTo(occursAtMs.toBigDecimal())
        assertThat(num(eval("k", "minutes_left"))).isEqualByComparingTo("90.5")
        assertThat(num(eval("k", "minutes_to_occurs"))).isEqualByComparingTo("30.5")
        assertThat(num(eval("k", "minutes_left", at = closeAtMs))).isEqualByComparingTo("0")
        assertThat(num(eval("k", "minutes_to_occurs", at = occursAtMs + 120_000L))).isEqualByComparingTo("-2")
        assertThat(listOf("occurs_at", "minutes_to_occurs").map { eval("b", it) }).containsOnly(Value.Undefined)
    }

    @Test
    fun `the title is a string, Undefined when the contract has none`() {
        assertThat(eval("k", "title")).isEqualTo(Value.Str("7,425 or above"))
        assertThat(eval("c", "title")).isEqualTo(Value.Undefined)
    }

    @Test
    fun `fees are one contract's unrounded fee at the ask for a taker and the bid for a maker`() {
        assertThat(num(eval("k", "taker_fee"))).isEqualByComparingTo("0.0175")
        assertThat(num(eval("k", "maker_fee"))).isEqualByComparingTo("0.0042")
        assertThat(eval("k", "taker_fee", ask = null)).isEqualTo(Value.Undefined)
        assertThat(eval("k", "maker_fee", bid = null)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `a payout other than one prices the fee on the price's share of it, and no maker rate is no maker fee`() {
        val tens = EventContracts.meta(terms = base.copy(payout = BigDecimal.TEN, fee = fee.copy(makerRate = null)))
        val instruments = EventContracts.registry(tens)

        assertThat(num(eval("k", "taker_fee", ask = "5", instruments = instruments))).isEqualByComparingTo("0.175")
        assertThat(num(eval("k", "maker_fee", bid = "4", instruments = instruments))).isEqualByComparingTo("0")
    }

    @Test
    fun `a stream that is not an event contract reads Undefined`() {
        assertThat(DslVocabulary.eventFields.map { eval("g", it) }).containsOnly(Value.Undefined)
    }

    private fun compiled(rule: String): DslCompiledStrategy {
        val text =
            "STRATEGY t VERSION 1\nSYMBOLS\n    k = $ladder EVERY 1m\n    b = $between EVERY 1m\n" +
                "    g = EXNESS:XAUUSD EVERY 1m\nRULES\n$rule\n"
        return AstCompiler().compile((Dsl.parse(text) as ParseResult.Success).value) as DslCompiledStrategy
    }

    private fun reads(rule: String): Set<EventRead> = (compiled(rule) as EventFieldReader).eventReads

    private fun start(rule: String) = requireEventTerms("s", compiled("$rule\n    THEN LOG \"x\""), registry)

    @Test
    fun `the fields a strategy reads are collected from rules and actions, and none when it reads none`() {
        assertThat(reads("    WHEN k.minutes_left < 30 AND b.floor_strike > 0\n    THEN LOG \"x\""))
            .containsExactlyInAnyOrder(EventRead(ladder, "minutes_left"), EventRead(between, "floor_strike"))
        assertThat(reads("    WHEN g.close > 0\n    THEN LOG \"fee\" f=k.taker_fee"))
            .containsExactly(EventRead(ladder, "taker_fee"))
        assertThat(reads("    WHEN g.close > 0\n    THEN LOG \"x\" p=k.close")).isEmpty()
    }

    @Test
    fun `a strategy reading them does not start on a non-event stream, nor reading a fee that is unknown`() {
        assertThatThrownBy { start("    WHEN g.minutes_left > 0") }
            .hasMessageContaining("reads minutes_left of EXNESS:XAUUSD but it is not a catalogued event contract")
        assertThatThrownBy { start("    WHEN b.maker_fee > 0") }
            .hasMessageContaining("reads maker_fee of $between but its fee model is unknown")
        assertThatCode { start("    WHEN b.minutes_left > 0 AND k.taker_fee < 0.02 AND k.title = 'x'") }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a field the event compiler does not know is refused, never read as a fee`() {
        assertThatThrownBy { eval("k", "bid_depth", compiler = EventFieldCompiler::compile) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("bid_depth")
    }
}
