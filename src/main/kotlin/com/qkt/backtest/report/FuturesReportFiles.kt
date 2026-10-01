package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.broker.continuous.ContractFill
import com.qkt.broker.continuous.RollEntry
import com.qkt.broker.exchange.Settlement

/**
 * The futures artifacts of a report — `rolls.csv`, `contracts.csv`, `settlements.csv`,
 * `margin_daily.csv` — each present
 * only when its ledger has entries, so a report of a run without futures is unchanged. The writer,
 * the artifact index and the manifest all list files from here. Columns follow `trades.csv`:
 * epoch-ms `timestamp`, plain decimals, quoted text. `rolls.csv` quantities are signed (negative
 * short) and its prices, fees and `rollCost` are in the root's currency; `margin_daily.csv` is in
 * account currency.
 */
internal object FuturesReportFiles {
    /** Artifact index key of each file. */
    val keys =
        mapOf("rolls.csv" to "rollsCsv", "contracts.csv" to "contractsCsv", "settlements.csv" to "settlementsCsv")

    /** The futures files of [result], name to content. */
    fun render(result: BacktestResult): List<Pair<String, String>> =
        render(result.rolls, result.contractFills, result.settlements)

    /** The files for these ledgers, name to content, omitting empty ones. */
    fun render(
        rolls: List<RollEntry>,
        fills: List<ContractFill>,
        settlements: List<Settlement>,
    ): List<Pair<String, String>> =
        buildList {
            if (rolls.isNotEmpty()) add("rolls.csv" to rolls(rolls))
            if (fills.isNotEmpty()) add("contracts.csv" to contracts(fills))
            if (settlements.isNotEmpty()) add("settlements.csv" to settlements(settlements))
        }

    private fun rolls(entries: List<RollEntry>): String =
        csv(
            "timestamp,stream,strategy,from,to,quantity,multiplier,fromReference,toReference,gap,fromFill,toFill,fees,rollCost",
            entries,
        ) {
            listOf(
                it.atMs.toString(),
                csvField(it.stream),
                csvField(it.strategyId),
                csvField(it.from),
                csvField(it.to),
                plain(it.quantity),
                plain(it.multiplier),
                plain(it.fromReference),
                plain(it.toReference),
                plain(it.toReference.subtract(it.fromReference)),
                plain(it.fromFill),
                plain(it.toFill),
                plain(it.fees),
                plain(it.cost),
            )
        }

    private fun contracts(entries: List<ContractFill>): String =
        csv("timestamp,strategy,stream,orderId,contract,side,quantity,contractPrice,streamPrice", entries) {
            listOf(
                it.atMs.toString(),
                csvField(it.strategyId),
                csvField(it.stream),
                csvField(it.orderId),
                csvField(it.contract),
                it.side.name,
                plain(it.quantity),
                plain(it.contractPrice),
                plain(it.streamPrice),
            )
        }

    private fun settlements(entries: List<Settlement>): String =
        csv("timestamp,strategy,contract,side,quantity,price,deliveryPriceKnown", entries) {
            listOf(
                it.atMs.toString(),
                csvField(it.strategyId),
                csvField(it.contract),
                it.side.name,
                plain(it.quantity),
                plain(it.price),
                it.deliveryPriceKnown.toString(),
            )
        }

    private fun <T> csv(
        header: String,
        rows: List<T>,
        cells: (T) -> List<String>,
    ): String =
        buildString {
            append(header).append('\n')
            for (row in rows) append(cells(row).joinToString(",")).append('\n')
        }

    private fun plain(value: java.math.BigDecimal): String = value.toPlainString()
}
