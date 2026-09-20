package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ReplayInputReport
import com.qkt.backtest.RunawayBreakerReport
import com.qkt.evidence.AccountingEvidence
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.ExecutionEvidence
import com.qkt.evidence.ExperimentEvidence
import com.qkt.evidence.PromotionEvidence
import com.qkt.risk.RunawayBreakerRule
import com.qkt.risk.RunawayBreakerTrip
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportPrinterEvidenceTest : ReportPrinterFixture() {
    @Test
    fun `reports carry replay input accounting`() {
        val inputs =
            ReplayInputReport(
                attemptedFeedTicks = 12,
                liveTicks = 10,
                warmupTicks = 8,
                warmupCandles = 2,
                liveCandles = 3,
                malformedTicks = 2,
                droppedLateTicks = 1,
                streamCandles = mapOf("EXNESS:EURUSD:5m" to 2L),
                strategyCandleEvaluations = mapOf("alpha:eur5:EXNESS:EURUSD:5m" to 2L),
            )
        val result = result().copy(inputSummary = inputs)

        val jsonOut = ByteArrayOutputStream()
        ReportPrinter.print(result, ReportFormat.Json, PrintStream(jsonOut), BrokerKind.PAPER)
        val json = Json.parseToJsonElement(jsonOut.toString()).jsonObject
        val summary = json.getValue("inputSummary").jsonObject
        assertThat(summary.getValue("attemptedFeedTicks").jsonPrimitive.content).isEqualTo("12")
        assertThat(summary.getValue("liveTicks").jsonPrimitive.content).isEqualTo("10")
        assertThat(summary.getValue("warmupTicks").jsonPrimitive.content).isEqualTo("8")
        assertThat(summary.getValue("warmupCandles").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("liveCandles").jsonPrimitive.content).isEqualTo("3")
        assertThat(summary.getValue("malformedTicks").jsonPrimitive.content).isEqualTo("2")
        assertThat(summary.getValue("droppedLateTicks").jsonPrimitive.content).isEqualTo("1")
        assertThat(
            summary
                .getValue("streamCandles")
                .jsonObject
                .getValue("EXNESS:EURUSD:5m")
                .jsonPrimitive
                .content,
        ).isEqualTo("2")
        assertThat(
            summary
                .getValue("strategyCandleEvaluations")
                .jsonObject
                .getValue("alpha:eur5:EXNESS:EURUSD:5m")
                .jsonPrimitive
                .content,
        ).isEqualTo("2")

        val textOut = ByteArrayOutputStream()
        ReportPrinter.print(result, ReportFormat.Text, PrintStream(textOut), BrokerKind.PAPER)
        assertThat(textOut.toString())
            .contains(
                "Replay inputs",
                "warmup ticks:    8",
                "late ticks:      1",
                "stream EXNESS:EURUSD:5m: 2 candles",
                "strategy evaluation alpha:eur5:EXNESS:EURUSD:5m: 2 candles",
            )
    }

    @Test
    fun `reports runaway breaker thresholds and live divergence`() {
        val breaker =
            RunawayBreakerReport(
                enforceLiveBreakers = false,
                maxRoundTrips = 10,
                roundTripWindowMs = 600_000L,
                maxRejections = 5,
                rejectionWindowMs = 60_000L,
                trips =
                    listOf(
                        RunawayBreakerTrip(
                            1_700_000_000_000L,
                            "fast",
                            RunawayBreakerRule.ROUND_TRIPS,
                            11,
                            10,
                            600_000L,
                        ),
                    ),
            )
        val result = result().copy(runawayBreaker = breaker)

        val text = ByteArrayOutputStream()
        ReportPrinter.print(result, ReportFormat.Text, PrintStream(text), BrokerKind.PAPER)
        assertThat(text.toString()).contains("Runaway breaker:  observe-only")
        assertThat(text.toString()).contains("LIVE BEHAVIOR WARNING")
        assertThat(text.toString()).contains("2023-11-14T22:13:20Z")

        val json = ByteArrayOutputStream()
        ReportPrinter.print(result, ReportFormat.Json, PrintStream(json), BrokerKind.PAPER)
        assertThat(json.toString()).contains("\"runawayBreaker\":{\"enforceLiveBreakers\":false")
        assertThat(json.toString()).contains("\"rule\":\"round_trips\"")
    }

    @Test
    fun `json and text reports carry run evidence when present`() {
        val res = result().copy(evidence = evidence())

        val json = ByteArrayOutputStream()
        ReportPrinter.print(res, ReportFormat.Json, PrintStream(json), BrokerKind.PAPER)
        assertThat(json.toString()).contains("\"evidence\":{\"qktVersion\":\"test\"")
        assertThat(json.toString()).contains("\"strategyHash\":\"sha256:strategy\"")
        assertThat(json.toString()).contains("\"preset\":\"paper-fast\"")
        assertThat(json.toString()).contains("\"experiment\":{\"id\":\"exp-1\"")
        assertThat(json.toString()).contains("\"accounting\":{\"accountCurrency\":\"USD\"")
        assertThat(json.toString()).contains("\"conversions\":{\"JPY->USD@context:USDJPY\":\"rate=0.006622516556291391")
        assertThat(json.toString()).contains("\"selectedParams\":{\"fast\":\"3\"}")
        assertThat(json.toString()).contains("\"rationale\":\"ready for paper\"")

        val text = ByteArrayOutputStream()
        ReportPrinter.print(res, ReportFormat.Text, PrintStream(text), BrokerKind.PAPER)
        assertThat(text.toString()).contains("Run evidence")
        assertThat(text.toString()).contains("sha256:strategy")
        assertThat(text.toString()).contains("mutable local store")
        assertThat(text.toString()).contains("account:   USD")
        assertThat(text.toString()).contains("fx JPY->USD@context:USDJPY")
        assertThat(text.toString()).contains("split.train: 2026-06-04T00:00:00Z/2026-06-04T04:00:00Z")
        assertThat(text.toString()).contains("promotion: candidate")
        assertThat(text.toString()).contains("rationale: ready for paper")
    }

    private fun evidence(): EvidenceEnvelope =
        EvidenceEnvelope(
            qktVersion = "test",
            gitSha = "abc123",
            buildTimestamp = "2026-06-25T00:00:00Z",
            command = listOf("backtest", "s.qkt"),
            strategyHash = "sha256:strategy",
            dataset = DatasetEvidence(mutableStore = true),
            execution = ExecutionEvidence(preset = "paper-fast", broker = "paper"),
            accounting =
                AccountingEvidence(
                    accountCurrency = "USD",
                    missingPolicy = "fail",
                    source = "market",
                    configuredFxSymbols = mapOf("USDJPY" to "BACKTEST:USDJPY"),
                    conversions =
                        mapOf(
                            "JPY->USD@context:USDJPY" to "rate=0.006622516556291391 timestamp=2000",
                        ),
                    costKinds = listOf("COMMISSION", "SWAP", "FUNDING"),
                ),
            experiment =
                ExperimentEvidence(
                    id = "exp-1",
                    trialCount = 2,
                    primaryMetric = "totalPnL",
                    splits =
                        mapOf(
                            "train" to "2026-06-04T00:00:00Z/2026-06-04T04:00:00Z",
                            "validation" to "2026-06-04T04:00:00Z/2026-06-04T08:00:00Z",
                            "test" to "2026-06-04T08:00:00Z/2026-06-04T12:00:00Z",
                        ),
                    selectedLabel = "fast=3",
                    selectedParams = mapOf("fast" to "3"),
                    warnings = listOf("large search"),
                ),
            promotion = PromotionEvidence(state = "candidate", rationale = "ready for paper"),
        )
}
