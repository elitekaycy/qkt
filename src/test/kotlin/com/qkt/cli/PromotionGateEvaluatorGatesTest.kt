package com.qkt.cli

import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PromotionGateEvaluatorGatesTest {
    @TempDir
    lateinit var tmp: Path

    private val now = Instant.parse("2026-11-01T00:00:00Z")

    private val strict =
        PromotionGateConfig(
            enforce = true,
            requiredState = PromotionState.PAPER,
            requireDatasetSnapshot = true,
            requireRealisticExecution = true,
            requireWalkForward = true,
            requireApproval = false,
            minPaperDays = 10,
            minPaperTrades = 50,
            maxPaperSlippageBps = 2.0,
        )

    private fun evaluate(paper: PaperValidationMetrics?): PromotionGateResult {
        val store = PromotionStore(tmp)
        store.append(PromotionRecord.create("alpha", "sha256:x", PromotionState.PAPER, "soak", now, paper = paper))
        return PromotionGateEvaluator(strict).evaluate("alpha", null, store, now = now)
    }

    @Test
    fun `a record without evidence or paper metrics misses every configured gate`() {
        val result = evaluate(paper = null)

        assertThat(result.eligibleForProduction).isFalse()
        assertThat(result.missingGates).containsExactly(
            "dataset_snapshot",
            "realistic_execution",
            "walk_forward",
            "paper_days",
            "paper_min_trades",
            "paper_slippage",
        )
    }

    @Test
    fun `short noisy or failed paper trading misses the paper gates`() {
        val result = evaluate(PaperValidationMetrics(days = 3, trades = 12, p95SlippageBps = 4.5, status = "FAIL"))

        assertThat(result.missingGates)
            .contains("paper_days", "paper_min_trades", "paper_slippage", "paper_status")
    }

    @Test
    fun `paper trading inside every limit passes the paper gates`() {
        val result = evaluate(PaperValidationMetrics(days = 12, trades = 80, p95SlippageBps = 1.5, status = "pass"))

        assertThat(
            result.missingGates,
        ).doesNotContain("paper_days", "paper_min_trades", "paper_slippage", "paper_status")
    }
}
