package com.qkt.derivatives.options.chain

import java.math.BigDecimal

/**
 * The row format of a chain file: a fixed [HEADER], one quote per line, an absent value as an empty
 * cell. Decimals are written with [BigDecimal.toString], which [BigDecimal]'s constructor reads back
 * to the same value and scale.
 */
internal object ChainCsv {
    const val HEADER = "atMs,contract,bid,ask,mark,markIv,underlying,rate,markAgeMs,source"
    private const val COLUMNS = 10

    fun row(q: ChainQuote): String =
        listOf(
            q.atMs.toString(),
            q.contract,
            cell(q.bid),
            cell(q.ask),
            cell(q.mark),
            cell(q.markIv),
            cell(q.underlying),
            cell(q.rate),
            q.markAgeMs.toString(),
            q.source.name,
        ).joinToString(",")

    /** The quote on [line]; throws [IllegalArgumentException] naming the line when it is malformed. */
    fun parse(line: String): ChainQuote {
        val cells = line.split(',')
        require(cells.size == COLUMNS) { "expected $COLUMNS columns, got ${cells.size}: $line" }

        fun decimal(i: Int): BigDecimal? = cells[i].takeIf { it.isNotEmpty() }?.let(::BigDecimal)
        return ChainQuote(
            atMs = cells[0].toLong(),
            contract = cells[1],
            bid = decimal(2),
            ask = decimal(3),
            mark = requireNotNull(decimal(4)) { "mark missing: $line" },
            markIv = decimal(5),
            underlying = requireNotNull(decimal(6)) { "underlying missing: $line" },
            rate = decimal(7),
            markAgeMs = cells[8].toLong(),
            source = QuoteSource.valueOf(cells[9]),
        )
    }

    private fun cell(value: BigDecimal?): String = value?.toString() ?: ""
}
