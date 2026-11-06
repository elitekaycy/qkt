package com.qkt.backtest.report

import com.qkt.evidence.AccountingEvidence
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.ExecutionEvidence
import com.qkt.evidence.ExperimentEvidence
import com.qkt.evidence.PromotionEvidence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlEvidenceTableTest {
    private val bare = EvidenceEnvelope(qktVersion = "1.0", gitSha = "abc", buildTimestamp = "t", strategyHash = "h")

    @Test
    fun `a bare envelope renders only the build rows`() {
        val html = HtmlEvidenceTable.render(bare)

        assertThat(html).contains("<td>qkt version</td><td>1.0</td>", "<td>strategy hash</td><td>h</td>")
        assertThat(html).doesNotContain("config hash", "dataset", "execution", "experiment", "promotion", "warnings")
    }

    @Test
    fun `empty sub-sections fall back to their defaults and skip absent fields`() {
        val html =
            HtmlEvidenceTable.render(
                bare.copy(
                    dataset = DatasetEvidence(),
                    execution = ExecutionEvidence(preset = "paper-fast", broker = "paper"),
                    accounting = AccountingEvidence(),
                    experiment = ExperimentEvidence(),
                    promotion = PromotionEvidence(),
                ),
            )

        assertThat(html).contains("<td>dataset</td><td>not specified</td>")
        assertThat(html).contains("<td>execution</td><td>paper-fast (paper)</td>")
        assertThat(html).contains("<td>experiment</td><td>unspecified</td>")
        assertThat(html).doesNotContain("fill price source", "account currency", "trial count", "promotion state")
    }

    @Test
    fun `a mutable store without an id is labelled as such`() {
        val html = HtmlEvidenceTable.render(bare.copy(dataset = DatasetEvidence(mutableStore = true)))

        assertThat(html).contains("<td>dataset</td><td>mutable local store</td>")
    }

    @Test
    fun `a full envelope renders every row escaped`() {
        val html =
            HtmlEvidenceTable.render(
                bare.copy(
                    configHash = "cfg",
                    dataset = DatasetEvidence(id = "ds<1>", warning = "stale"),
                    execution =
                        ExecutionEvidence(
                            preset = "stress",
                            broker = "mt5-sim",
                            fillPriceSource = "bid/ask",
                            latencyModel = "fixed:5ms",
                            stopLatencyModel = "on-trigger",
                            takeProfitFillModel = "print",
                            candleCloseModel = "heartbeat",
                            slippageModel = "zero",
                            rejectionModel = "none",
                            partialFillModel = "none",
                            venueRules = "rules",
                            commissionModel = "per-lot",
                            financingModel = "swap",
                            ocoMode = "engine",
                            warning = "adverse",
                        ),
                    accounting =
                        AccountingEvidence(
                            accountCurrency = "USD",
                            missingPolicy = "fail",
                            source = "store",
                            configuredFxSymbols = mapOf("EUR" to "EURUSD"),
                            conversions = mapOf("EUR" to "1.1"),
                            warnings = listOf("w1", "w2"),
                            warning = "fx gap",
                        ),
                    experiment =
                        ExperimentEvidence(
                            id = "exp-1",
                            trialCount = 12,
                            primaryMetric = "sharpe",
                            splits = mapOf("train" to "2020"),
                            selectedLabel = "best",
                            selectedParams = mapOf("n" to "3"),
                            warnings = listOf("x"),
                            warning = "overfit",
                        ),
                    promotion = PromotionEvidence(state = "candidate", rationale = "ok", warning = "unsoaked"),
                    warnings = listOf("top"),
                ),
            )

        val labels =
            listOf(
                "config hash",
                "dataset warning",
                "fill price source",
                "latency model",
                "stop execution delay",
                "take-profit fill",
                "quiet-bar close",
                "slippage model",
                "rejection model",
                "partial-fill model",
                "venue rules",
                "cost model",
                "financing model",
                "OCO mode",
                "execution warning",
                "account currency",
                "FX missing policy",
                "FX source",
                "FX symbols",
                "FX conversions",
                "accounting warnings",
                "accounting warning",
                "trial count",
                "primary metric",
                "split.train",
                "selected candidate",
                "experiment warnings",
                "experiment warning",
                "promotion state",
                "promotion rationale",
                "promotion warning",
                "warnings",
            )
        labels.forEach { assertThat(html).contains("<td>$it</td>") }
        assertThat(html).contains("<td>dataset</td><td>ds&lt;1&gt;</td>")
        assertThat(html).contains("<td>selected candidate</td><td>best {n=3}</td>")
        assertThat(html).contains("<td>accounting warnings</td><td>w1; w2</td>")
    }
}
