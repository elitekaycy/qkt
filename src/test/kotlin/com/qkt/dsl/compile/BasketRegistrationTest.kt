package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.strategy.PerStreamWarmable
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BasketRegistrationTest {
    private fun compile(src: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private val src =
        """
        STRATEGY reg VERSION 1
        SYMBOLS
            aud = EXNESS:AUDUSD EVERY 1h
            nzd = EXNESS:NZDUSD EVERY 1h
            antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
        RULES
            WHEN aud.close > 0 THEN BUY aud SIZING 0.1
        """.trimIndent()

    @Test
    fun `basket alias is registered as a synthetic BASKET stream`() {
        val s = compile(src)
        assertThat(s.declaredStreams).containsKey("antipodean")
        assertThat(s.declaredStreams.getValue("antipodean"))
            .isEqualTo(HubKey("BASKET", "ANTIPODEAN", "1h"))
    }

    @Test
    fun `real constituents remain registered alongside the basket`() {
        val s = compile(src)
        assertThat(s.declaredStreams.getValue("aud")).isEqualTo(HubKey("EXNESS", "AUDUSD", "1h"))
        assertThat(s.declaredStreams.getValue("nzd")).isEqualTo(HubKey("EXNESS", "NZDUSD", "1h"))
    }

    @Test
    fun `retention covers the basket key`() {
        val s = compile(src)
        val basketKey = HubKey("BASKET", "ANTIPODEAN", "1h")
        assertThat(s.retentionByKey).containsKey(basketKey)
        assertThat(s.retentionByKey.getValue(basketKey)).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `an indicator over a basket asks its constituents for history, not the composite`() {
        val s =
            compile(
                """
                STRATEGY warm VERSION 1
                SYMBOLS
                    aud = EXNESS:AUDUSD EVERY 1h
                    nzd = EXNESS:NZDUSD EVERY 1h
                    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
                RULES
                    WHEN antipodean.close > sma(antipodean.close, 20) THEN BUY antipodean SIZING 300 USD
                """.trimIndent(),
            )
        val window = TimeWindow.parse("1h")
        val warmup = (s as PerStreamWarmable).perStreamWarmup
        assertThat(warmup).containsOnlyKeys(
            WarmupStream("EXNESS:AUDUSD", window),
            WarmupStream("EXNESS:NZDUSD", window),
        )
        assertThat(warmup.values).containsOnly(WarmupSpec.Bars(window, 21))
    }
}
