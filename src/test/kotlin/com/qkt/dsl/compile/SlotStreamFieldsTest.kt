package com.qkt.dsl.compile

import com.qkt.app.requireEventTerms
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A slot's `contract` and event fields read the contract it followed for its latest ticks. */
class SlotStreamFieldsTest {
    private val slot = "KALSHI:KXS@front.p70"
    private val at = SlotFamilyFixture.closeMs(1) - 30 * 60_000L

    private fun eval(
        field: String,
        followed: String?,
    ): Value {
        val source =
            object : MarketSource {
                override val name = "slots"
                override val capabilities = emptySet<MarketSourceCapability>()

                override fun supports(symbol: String) = true

                override fun slotContractAt(
                    symbol: String,
                    atMs: Long,
                ): String? = followed
            }
        val one = BigDecimal.ONE
        val ctx =
            EvalContext(
                candle = Candle(slot, one, one, one, one, one, at - 60_000L, at),
                streams = mapOf("p70" to HubKey("KALSHI", "KXS@front.p70", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(source = source, instruments = SlotFamilyFixture.registry()),
                evaluationTimeMs = at,
            )
        return ExprCompiler().compile(StreamFieldRef("p70", field)).evaluate(ctx)
    }

    @Test
    fun `contract is the followed contract's code and the day counts are undefined`() {
        assertThat(eval("contract", symbol(1, 2))).isEqualTo(Value.Str("KXS_H1_T2"))
        assertThat(eval("dte", symbol(1, 2))).isEqualTo(Value.Undefined)
        assertThat(eval("contract", null)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `event fields read the followed contract's terms`() {
        assertThat((eval("floor_strike", symbol(1, 3)) as Value.Num).v).isEqualByComparingTo("300")
        assertThat((eval("minutes_left", symbol(1, 3)) as Value.Num).v).isEqualByComparingTo("30")
        assertThat(eval("floor_strike", null)).isEqualTo(Value.Undefined)
    }

    private fun compiled(): DslCompiledStrategy {
        val text =
            "STRATEGY t VERSION 1\nSYMBOLS\n    p = $slot EVERY 1m\nRULES\n    WHEN p.taker_fee < 1 THEN LOG \"x\"\n"
        return AstCompiler().compile((Dsl.parse(text) as ParseResult.Success).value) as DslCompiledStrategy
    }

    @Test
    fun `a slot of a declared family may read event fields, one of no family may not`() {
        assertThatCode { requireEventTerms("s", compiled(), SlotFamilyFixture.registry()) }.doesNotThrowAnyException()
        assertThatThrownBy { requireEventTerms("s", compiled(), NoopInstrumentRegistry) }
            .hasMessageContaining("not a catalogued event contract")
    }
}
