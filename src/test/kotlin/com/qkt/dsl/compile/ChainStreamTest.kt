package com.qkt.dsl.compile

import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.ChainMetric
import com.qkt.dsl.ast.CHAIN_BROKER
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChainStreamTest {
    private fun strategy(action: String) =
        """
        STRATEGY skewed VERSION 1
        SYMBOLS
            iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.30d EVERY 1h
            c = DERIBIT:BTC_USDC_26SEP26_84000_C EVERY 1h
        RULES
            WHEN iv.close > 40
            THEN $action
        """.trimIndent()

    @Test
    fun `a chain analytics stream parses as a dotted observation symbol`() {
        val stream = (Dsl.parse(strategy("BUY c SIZING 0.1")) as ParseResult.Success).value.streams.first()

        assertThat(stream.broker).isEqualTo(CHAIN_BROKER)
        assertThat(stream.symbol).isEqualTo("DERIBIT.BTC_USDC.atm_iv.30d")
        assertThat(stream.qktSymbol).isEqualTo("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d")
        assertThat(isObservationSymbol(stream.qktSymbol)).isTrue()
    }

    @Test
    fun `an analytics stream cannot be traded`() {
        assertThatThrownBy {
            AstCompiler().compile(
                (Dsl.parse(strategy("BUY iv SIZING 1")) as ParseResult.Success).value,
            )
        }.isInstanceOf(CompileError::class.java)
            .hasMessageContaining("read-only")
    }

    @Test
    fun `the symbol names its root, metric and tenor, and a malformed one says what is wrong`() {
        val parsed = ChainAnalyticsSymbol.parse("CHAIN:DERIBIT.BTC_USDC.skew_25d.7d").getOrThrow()

        assertThat(parsed.root).isEqualTo("DERIBIT:BTC_USDC")
        assertThat(parsed.metric).isEqualTo(ChainMetric.SKEW_25D)
        assertThat(parsed.tenorDays).isEqualTo(7)
        assertThat(ChainAnalyticsSymbol.parse("CHAIN:DERIBIT.BTC_USDC.put_call_oi.30d").exceptionOrNull())
            .hasMessageContaining("atm_iv, skew_25d")
        assertThat(
            ChainAnalyticsSymbol.parse("CHAIN:DERIBIT.BTC_USDC.atm_iv.30h").exceptionOrNull(),
        ).hasMessageContaining("tenor")
        assertThat(ChainAnalyticsSymbol.parse("CHAIN:DERIBIT.atm_iv.30d").exceptionOrNull())
            .hasMessageContaining("CHAIN:<VENUE>.<ROOT>.<metric>.<tenor>")
    }
}
