package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.RunawayBreakerReport
import com.qkt.backtest.SampleCadence
import com.qkt.risk.RunawayBreakerRule
import com.qkt.risk.RunawayBreakerTrip
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultJsonRunawayTest {
    private val zero = BigDecimal.ZERO
    private val report =
        PerformanceReport(
            realizedTotal = zero,
            unrealizedTotal = zero,
            totalPnL = zero,
            tradeCount = 0,
            winRate = zero,
            maxDrawdown = zero,
            profitFactor = null,
            avgWin = zero,
            avgLoss = zero,
            largestWin = zero,
            largestLoss = zero,
            maxConsecutiveLosses = 0,
            sharpeRatio = null,
            calmarRatio = null,
            equityCurve = emptyList(),
        )

    @Test
    fun `runaway breaker trips are written with their rule, count and window`() {
        val trip = RunawayBreakerTrip(90L, "s1", RunawayBreakerRule.BROKER_REJECTIONS, 6, 5, 60_000L)
        val result =
            BacktestResult(
                trades = emptyList(),
                rejections = emptyList(),
                finalPositions = emptyMap(),
                global = report,
                perStrategy = emptyMap(),
                cadence = SampleCadence.FILL,
                runawayBreaker = RunawayBreakerReport(true, 3, 1_000L, 5, 60_000L, listOf(trip)),
            )

        val breaker =
            Json
                .parseToJsonElement(
                    ResultJson.render(result),
                ).jsonObject
                .getValue("runawayBreaker")
                .jsonObject
        val written =
            breaker
                .getValue("trips")
                .jsonArray
                .single()
                .jsonObject

        assertThat(breaker.getValue("enforceLiveBreakers").jsonPrimitive.content).isEqualTo("true")
        assertThat(written.getValue("strategyId").jsonPrimitive.content).isEqualTo("s1")
        assertThat(written.getValue("rule").jsonPrimitive.content).isEqualTo("broker_rejections")
        assertThat(written.getValue("count").jsonPrimitive.content).isEqualTo("6")
        assertThat(written.getValue("threshold").jsonPrimitive.content).isEqualTo("5")
        assertThat(written.getValue("windowMs").jsonPrimitive.content).isEqualTo("60000")
    }
}
