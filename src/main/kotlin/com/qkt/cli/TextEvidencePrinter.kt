package com.qkt.cli

import com.qkt.backtest.BacktestResult
import java.io.PrintStream

/**
 * The "Run evidence" block of the plaintext backtest report: build provenance, dataset, execution
 * model, accounting, experiment and promotion fields. Skipped when the run carries no evidence.
 */
internal object TextEvidencePrinter {
    fun print(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val e = r.evidence ?: return
        out.println()
        out.println("Run evidence")
        out.println("  qkt:       ${e.qktVersion} (${e.gitSha})")
        out.println("  pct DSL:   ${e.dslPercentConvention}")
        out.println("  strategy:  ${e.strategyHash}")
        e.configHash?.let { out.println("  config:    $it") }
        e.dataset?.let {
            val label = it.id ?: if (it.mutableStore) "mutable local store" else "not specified"
            out.println("  dataset:   $label")
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.execution?.let {
            out.println("  execution: ${it.preset} (${it.broker})")
            it.fillPriceSource?.let { v -> out.println("  fills:     $v") }
            it.latencyModel?.let { v -> out.println("  latency:   $v") }
            it.stopLatencyModel?.let { v -> out.println("  stop delay: $v") }
            it.takeProfitFillModel?.let { v -> out.println("  tp fill:   $v") }
            it.candleCloseModel?.let { v -> out.println("  bar close: $v") }
            it.slippageModel?.let { v -> out.println("  slippage:  $v") }
            it.rejectionModel?.let { v -> out.println("  rejects:   $v") }
            it.partialFillModel?.let { v -> out.println("  partials:  $v") }
            it.venueRules?.let { v -> out.println("  venue:     $v") }
            it.commissionModel?.let { v -> out.println("  costs:     $v") }
            it.financingModel?.let { v -> out.println("  financing: $v") }
            it.ocoMode?.let { v -> out.println("  oco:       $v") }
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.accounting?.let {
            it.accountCurrency?.let { ccy -> out.println("  account:   $ccy") }
            it.missingPolicy?.let { policy -> out.println("  fx policy: $policy") }
            it.source?.let { source -> out.println("  fx source: $source") }
            if (it.configuredFxSymbols.isNotEmpty()) {
                out.println("  fx symbols:${it.configuredFxSymbols}")
            }
            for ((pair, detail) in it.conversions.entries.sortedBy { entry -> entry.key }) {
                out.println("  fx $pair: $detail")
            }
            for (warning in it.warnings) out.println("  warning:   $warning")
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.experiment?.let {
            out.println("  experiment:${it.id ?: "unspecified"}")
            it.trialCount?.let { n -> out.println("  trials:    $n") }
            it.primaryMetric?.let { metric -> out.println("  metric:    $metric") }
            if (it.splits.isNotEmpty()) {
                for ((name, window) in it.splits.entries) {
                    out.println("  split.$name: $window")
                }
            }
            it.selectedLabel?.let { label -> out.println("  selected:  $label ${it.selectedParams}") }
            for (warning in it.warnings) out.println("  warning:   $warning")
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.promotion?.let {
            it.state?.let { state -> out.println("  promotion: $state") }
            it.rationale?.let { rationale -> out.println("  rationale: $rationale") }
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        for (warning in e.warnings) out.println("  warning:   $warning")
    }
}
