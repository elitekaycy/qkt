package com.qkt.backtest.report

import com.qkt.evidence.EvidenceEnvelope

/**
 * The "Run evidence" table of the HTML report: build provenance, dataset, execution model,
 * accounting, experiment and promotion fields, each row present only when the envelope has it.
 */
internal object HtmlEvidenceTable {
    fun render(e: EvidenceEnvelope): String =
        buildString {
            append("<table><tbody>")
            append("<tr><td>qkt version</td><td>${htmlEscape(e.qktVersion)}</td></tr>")
            append("<tr><td>git SHA</td><td>${htmlEscape(e.gitSha)}</td></tr>")
            append("<tr><td>DSL percentages</td><td>${htmlEscape(e.dslPercentConvention)}</td></tr>")
            append("<tr><td>strategy hash</td><td>${htmlEscape(e.strategyHash)}</td></tr>")
            e.configHash?.let { append("<tr><td>config hash</td><td>${htmlEscape(it)}</td></tr>") }
            e.dataset?.let {
                val label =
                    if (it.id != null) {
                        it.id
                    } else if (it.mutableStore) {
                        "mutable local store"
                    } else {
                        "not specified"
                    }
                append("<tr><td>dataset</td><td>${htmlEscape(label)}</td></tr>")
                it.warning?.let { warning ->
                    append("<tr><td>dataset warning</td><td>${htmlEscape(warning)}</td></tr>")
                }
            }
            e.execution?.let {
                append("<tr><td>execution</td><td>${htmlEscape(it.preset)} (${htmlEscape(it.broker)})</td></tr>")
                it.fillPriceSource?.let { v -> append("<tr><td>fill price source</td><td>${htmlEscape(v)}</td></tr>") }
                it.latencyModel?.let { v -> append("<tr><td>latency model</td><td>${htmlEscape(v)}</td></tr>") }
                it.stopLatencyModel?.let { v ->
                    append("<tr><td>stop execution delay</td><td>${htmlEscape(v)}</td></tr>")
                }
                it.takeProfitFillModel?.let { v ->
                    append("<tr><td>take-profit fill</td><td>${htmlEscape(v)}</td></tr>")
                }
                it.candleCloseModel?.let { v -> append("<tr><td>quiet-bar close</td><td>${htmlEscape(v)}</td></tr>") }
                it.slippageModel?.let { v -> append("<tr><td>slippage model</td><td>${htmlEscape(v)}</td></tr>") }
                it.rejectionModel?.let { v -> append("<tr><td>rejection model</td><td>${htmlEscape(v)}</td></tr>") }
                it.partialFillModel?.let { v ->
                    append("<tr><td>partial-fill model</td><td>${htmlEscape(v)}</td></tr>")
                }
                it.venueRules?.let { v -> append("<tr><td>venue rules</td><td>${htmlEscape(v)}</td></tr>") }
                it.commissionModel?.let { v -> append("<tr><td>cost model</td><td>${htmlEscape(v)}</td></tr>") }
                it.financingModel?.let { v -> append("<tr><td>financing model</td><td>${htmlEscape(v)}</td></tr>") }
                it.ocoMode?.let { v -> append("<tr><td>OCO mode</td><td>${htmlEscape(v)}</td></tr>") }
                it.warning?.let { warning ->
                    append("<tr><td>execution warning</td><td>${htmlEscape(warning)}</td></tr>")
                }
            }
            e.accounting?.let {
                it.accountCurrency?.let { ccy ->
                    append("<tr><td>account currency</td><td>${htmlEscape(ccy)}</td></tr>")
                }
                it.missingPolicy?.let { policy ->
                    append("<tr><td>FX missing policy</td><td>${htmlEscape(policy)}</td></tr>")
                }
                it.source?.let { source -> append("<tr><td>FX source</td><td>${htmlEscape(source)}</td></tr>") }
                if (it.configuredFxSymbols.isNotEmpty()) {
                    append("<tr><td>FX symbols</td><td>${htmlEscape(it.configuredFxSymbols.toString())}</td></tr>")
                }
                if (it.conversions.isNotEmpty()) {
                    append("<tr><td>FX conversions</td><td>${htmlEscape(it.conversions.toString())}</td></tr>")
                }
                if (it.warnings.isNotEmpty()) {
                    append(
                        "<tr><td>accounting warnings</td><td>${htmlEscape(it.warnings.joinToString("; "))}</td></tr>",
                    )
                }
                it.warning?.let { warning ->
                    append("<tr><td>accounting warning</td><td>${htmlEscape(warning)}</td></tr>")
                }
            }
            e.experiment?.let {
                append("<tr><td>experiment</td><td>${htmlEscape(it.id ?: "unspecified")}</td></tr>")
                it.trialCount?.let { n -> append("<tr><td>trial count</td><td>$n</td></tr>") }
                it.primaryMetric?.let { metric ->
                    append("<tr><td>primary metric</td><td>${htmlEscape(metric)}</td></tr>")
                }
                for ((name, window) in it.splits.entries) {
                    append("<tr><td>split.${htmlEscape(name)}</td><td>${htmlEscape(window)}</td></tr>")
                }
                it.selectedLabel?.let { label ->
                    append("<tr><td>selected candidate</td><td>")
                    append(htmlEscape(label))
                    append(" ")
                    append(htmlEscape(it.selectedParams.toString()))
                    append("</td></tr>")
                }
                if (it.warnings.isNotEmpty()) {
                    append(
                        "<tr><td>experiment warnings</td><td>${htmlEscape(it.warnings.joinToString("; "))}</td></tr>",
                    )
                }
                it.warning?.let { warning ->
                    append("<tr><td>experiment warning</td><td>${htmlEscape(warning)}</td></tr>")
                }
            }
            e.promotion?.let {
                it.state?.let { state -> append("<tr><td>promotion state</td><td>${htmlEscape(state)}</td></tr>") }
                it.rationale?.let { rationale ->
                    append("<tr><td>promotion rationale</td><td>${htmlEscape(rationale)}</td></tr>")
                }
                it.warning?.let { warning ->
                    append("<tr><td>promotion warning</td><td>${htmlEscape(warning)}</td></tr>")
                }
            }
            if (e.warnings.isNotEmpty()) {
                append("<tr><td>warnings</td><td>${htmlEscape(e.warnings.joinToString("; "))}</td></tr>")
            }
            append("</tbody></table>")
        }
}
