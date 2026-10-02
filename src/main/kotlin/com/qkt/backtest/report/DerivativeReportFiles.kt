package com.qkt.backtest.report

import com.qkt.accounting.margin.MarginDay
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.StructureRow
import com.qkt.broker.continuous.ContractFill
import com.qkt.broker.continuous.RollEntry
import com.qkt.broker.exchange.Settlement

/**
 * The futures and options artifacts of a report — `rolls.csv`, `contracts.csv`, `settlements.csv`,
 * `margin_daily.csv`, `structures.csv` — each present only when its ledger has entries, so a report of
 * a run without derivatives is unchanged. The writer,
 * the artifact index and the manifest all list files from here. Columns follow `trades.csv`:
 * epoch-ms `timestamp`, plain decimals, quoted text. `rolls.csv` quantities are signed (negative
 * short) and its prices, fees and `rollCost` are in the root's currency; `margin_daily.csv` is in
 * account currency; `structures.csv` legs read `SIDE quantity symbol @ entry`, separated by `;`, and
 * its `credit` and `realized` (premium P&L before fees) are in the root's currency, empty while unknown.
 */
internal object DerivativeReportFiles {
    /** Artifact index key of each file. */
    val keys =
        mapOf(
            "rolls.csv" to "rollsCsv",
            "contracts.csv" to "contractsCsv",
            "settlements.csv" to "settlementsCsv",
            "margin_daily.csv" to "marginDailyCsv",
            "structures.csv" to "structuresCsv",
        )

    /** The futures and options files of [result], name to content. */
    fun render(result: BacktestResult): List<Pair<String, String>> =
        render(result.rolls, result.contractFills, result.settlements) + marginDaily(result.marginDaily) +
            structures(result.structures)

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

    private fun marginDaily(days: List<MarginDay>): List<Pair<String, String>> {
        if (days.isEmpty()) return emptyList()
        val body =
            csv("date,marginUsed,maintenance,equity,marginCall", days) {
                listOf(
                    it.date.toString(),
                    plain(it.marginUsed),
                    plain(it.maintenance),
                    plain(it.equity),
                    it.marginCall.toString(),
                )
            }
        return listOf("margin_daily.csv" to body)
    }

    /** `structures.csv` for [rows], name to content; nothing when there are none. */
    fun structures(rows: List<StructureRow>): List<Pair<String, String>> {
        if (rows.isEmpty()) return emptyList()
        val body =
            csv("openedAt,closedAt,strategy,structure,alias,outcome,legs,credit,realized", rows) { row ->
                listOf(
                    row.openedAt?.toString().orEmpty(),
                    row.closedAt?.toString().orEmpty(),
                    csvField(row.strategyId),
                    csvField(row.structureId),
                    csvField(row.alias),
                    row.outcome?.name.orEmpty(),
                    csvField(legsOf(row)),
                    row.credit?.let(::plain).orEmpty(),
                    row.realized?.let(::plain).orEmpty(),
                )
            }
        return listOf("structures.csv" to body)
    }

    private fun legsOf(row: StructureRow): String =
        row.legs
            .mapNotNull { leg ->
                val entry = leg.entryPrice ?: return@mapNotNull null
                val side = if (leg.entryQuantity.signum() > 0) "BUY" else "SELL"
                "$side ${plain(leg.entryQuantity.abs())} ${leg.symbol} @ ${plain(entry)}"
            }.joinToString(";")

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
