package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Corporate actions, one CSV per symbol under `<dataRoot>/corporate-actions/<VENUE>/<NAME>.csv`
 * (`ex_date,kind,value,currency,pay_date`: `dividend` with its amount per share and currency, pay date optional;
 * `split` with its ratio), the same file whatever the source. Two actions of one kind on one ex-date are refused:
 * a duplicate dividend would be paid twice, a duplicate split applied twice.
 */
class CorporateActionStore(
    private val dataRoot: Path,
) : CorporateActionSource {
    /** Where [qktSymbol]'s actions live. */
    fun path(qktSymbol: String): Path {
        QktSymbols.requireFileSafe(qktSymbol)
        return dataRoot
            .resolve("corporate-actions")
            .resolve(qktSymbol.substringBefore(':'))
            .resolve("${qktSymbol.substringAfter(':')}.csv")
    }

    override fun actions(qktSymbol: String): List<CorporateAction> {
        // A derived stream (OI:..., DEPTH:...) or a bare research symbol has no file, so no corporate actions.
        if (!QktSymbols.isFileSafe(qktSymbol)) return emptyList()
        val file = path(qktSymbol)
        if (!Files.exists(file)) return emptyList()
        val actions =
            Files.readAllLines(file).drop(1).filter { it.isNotBlank() }.mapIndexed { i, line ->
                runCatching {
                    parse(
                        line,
                    )
                }.getOrElse { throw IllegalArgumentException("$file line ${i + 2}: ${it.message}") }
            }
        checkUnique(file.toString(), actions)
        return actions.sortedBy { it.exDate }
    }

    /** Adds [actions] to [qktSymbol]'s file, one of a kind on a date already stored replaced; returns how many it holds. */
    fun merge(
        qktSymbol: String,
        actions: List<CorporateAction>,
    ): Int {
        checkUnique("the actions to add", actions)
        val all =
            (actions(qktSymbol) + actions)
                .associateBy { it.exDate to it::class }
                .values
                .sortedWith(compareBy({ it.exDate }, { it::class.simpleName }))
        val file = path(qktSymbol)
        Files.createDirectories(file.parent)
        val staged = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(staged, HEADER + all.joinToString("") { format(it) + "\n" })
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return all.size
    }

    private fun checkUnique(
        where: String,
        actions: List<CorporateAction>,
    ) {
        val (date, kind) =
            actions
                .groupBy { it.exDate to it::class }
                .filterValues { it.size > 1 }
                .keys
                .firstOrNull() ?: return
        throw IllegalArgumentException("$where: two ${kind.simpleName?.lowercase()} actions on $date")
    }

    private companion object {
        const val HEADER = "ex_date,kind,value,currency,pay_date\n"

        fun parse(line: String): CorporateAction {
            val cells = line.split(',').map(String::trim)
            require(cells.size == 5) { "expected ex_date,kind,value,currency,pay_date: $line" }
            val (exDate, kind, value, currency, payDate) = cells
            return when (kind) {
                "dividend" -> {
                    require(currency.isNotEmpty()) { "a dividend needs its currency: $line" }
                    CorporateAction.Dividend(
                        LocalDate.parse(exDate),
                        BigDecimal(value),
                        currency,
                        payDate.takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                    )
                }
                "split" -> CorporateAction.Split(LocalDate.parse(exDate), BigDecimal(value))
                else -> throw IllegalArgumentException("kind must be dividend or split, not '$kind'")
            }
        }

        fun format(a: CorporateAction): String =
            when (a) {
                is CorporateAction.Dividend ->
                    "${a.exDate},dividend,${a.amount.toPlainString()},${a.currency},${a.payDate ?: ""}"
                is CorporateAction.Split -> "${a.exDate},split,${a.ratio.toPlainString()},,"
            }
    }
}
