package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SequenceRuntimeTest {
    private val values = mutableMapOf<String, Boolean>()

    private fun runtime(): SequenceRuntime =
        SequenceRuntime(
            listOf(
                CompiledSequence(
                    name = "sweep",
                    streamAlias = "gold",
                    streamSymbol = "BACKTEST:XAUUSD",
                    stages =
                        listOf(
                            CompiledSequenceStage("swept", null, expr("swept")),
                            CompiledSequenceStage("reclaimed", 30_000L, expr("reclaimed")),
                            CompiledSequenceStage("go", 15_000L, expr("go")),
                        ),
                    referencedAliases = setOf("gold"),
                ),
            ),
        )

    private fun expr(name: String): CompiledExpr = CompiledExpr { Value.of(values[name] == true) }

    private fun candle(
        close: String,
        endTime: Long,
    ): Candle =
        Candle(
            symbol = "BACKTEST:XAUUSD",
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ZERO,
            startTime = endTime - 60_000L,
            endTime = endTime,
        )

    private fun ctx(
        candle: Candle,
        runtime: SequenceRuntime,
    ): EvalContext =
        EvalContext(
            candle = candle,
            streams = mapOf("gold" to HubKey("BACKTEST", "XAUUSD", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
            currentAlias = "gold",
            evaluationTimeMs = candle.endTime,
            sequences = runtime,
        )

    private fun SequenceRuntime.on(
        close: String,
        endTime: Long,
    ) {
        val c = candle(close, endTime)
        onCandle(c, ctx(c, this))
    }

    @Test
    fun `advances stages in order and snapshots close price and time`() {
        val runtime = runtime()

        values["swept"] = true
        runtime.on("98.50", 1_000L)

        assertThat(runtime.stage("sweep")).isEqualTo(1)
        assertThat(runtime.stagePrice("sweep", "swept")).isEqualByComparingTo("98.50")
        assertThat(runtime.stageTime("sweep", "swept")).isEqualTo(1_000L)

        values["reclaimed"] = true
        runtime.on("101.00", 20_000L)

        assertThat(runtime.stage("sweep")).isEqualTo(2)
        assertThat(runtime.stagePrice("sweep", "reclaimed")).isEqualByComparingTo("101.00")
    }

    @Test
    fun `later stage already true does not skip or fire until a fresh edge`() {
        val runtime = runtime()

        values["reclaimed"] = true
        runtime.on("101.00", 1_000L)
        assertThat(runtime.stage("sweep")).isZero()

        values["swept"] = true
        runtime.on("98.00", 2_000L)
        assertThat(runtime.stage("sweep")).isEqualTo(1)

        runtime.on("101.00", 3_000L)
        assertThat(runtime.stage("sweep")).isEqualTo(1)

        values["reclaimed"] = false
        runtime.on("99.00", 4_000L)
        values["reclaimed"] = true
        runtime.on("101.00", 5_000L)
        assertThat(runtime.stage("sweep")).isEqualTo(2)
    }

    @Test
    fun `within timeout resets the whole sequence`() {
        val runtime = runtime()

        values["swept"] = true
        runtime.on("98.00", 1_000L)
        assertThat(runtime.stage("sweep")).isEqualTo(1)

        values["swept"] = false
        values["reclaimed"] = true
        runtime.on("101.00", 31_001L)

        assertThat(runtime.stage("sweep")).isZero()
        assertThat(runtime.stagePrice("sweep", "swept")).isNull()
    }

    @Test
    fun `complete is a one-pass pulse and sequence re-arms after rule pass`() {
        val runtime = runtime()

        values["swept"] = true
        runtime.on("98.00", 1_000L)
        values["reclaimed"] = true
        runtime.on("101.00", 2_000L)
        values["go"] = true
        runtime.on("102.00", 3_000L)

        assertThat(runtime.complete("sweep")).isTrue
        runtime.afterRulePass()
        assertThat(runtime.complete("sweep")).isFalse
        assertThat(runtime.stage("sweep")).isZero()

        values.clear()
        values["swept"] = true
        runtime.on("97.00", 4_000L)
        assertThat(runtime.stage("sweep")).isEqualTo(1)
    }
}
