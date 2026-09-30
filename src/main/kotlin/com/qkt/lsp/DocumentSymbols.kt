package com.qkt.lsp

import com.qkt.dsl.ast.BasketDecl
import com.qkt.dsl.ast.SequenceDecl
import com.qkt.dsl.ast.SeriesDecl
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.parse.ParsedFile

/**
 * The names one `.qkt` document declares, read off its last good AST: venue streams, baskets,
 * account series, sequences, LETs, PARAMs and portfolio imports. Completion offers every one of
 * them; member completion and hover look a specific kind up by alias.
 */
internal data class DocumentSymbols(
    val streams: List<StreamDecl>,
    val baskets: List<BasketDecl>,
    val series: List<SeriesDecl>,
    val sequences: List<SequenceDecl>,
    val lets: List<String>,
    val params: List<String>,
    val imports: List<String>,
) {
    val streamAliases: Set<String> get() = streams.mapTo(LinkedHashSet()) { it.alias }
    val basketAliases: Set<String> get() = baskets.mapTo(LinkedHashSet()) { it.alias }
    val seriesAliases: Set<String> get() = series.mapTo(LinkedHashSet()) { it.alias }

    /** Every alias a position or order can be read on: streams and baskets. */
    val positionAliases: List<String> get() = streams.map { it.alias } + baskets.map { it.alias }

    /** Every declared name, in declaration-group order, for general completion. */
    val names: List<String>
        get() =
            streams.map { it.alias } +
                baskets.map { it.alias } +
                series.map { it.alias } +
                sequences.map { it.name } +
                lets +
                params +
                imports

    fun sequence(name: String): SequenceDecl? = sequences.firstOrNull { it.name == name }

    companion object {
        val EMPTY =
            DocumentSymbols(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        fun of(ast: ParsedFile?): DocumentSymbols =
            when (ast) {
                is ParsedFile.StrategyFile ->
                    DocumentSymbols(
                        streams = ast.ast.streams,
                        baskets = ast.ast.baskets,
                        series = ast.ast.series,
                        sequences = ast.ast.sequences,
                        lets = ast.ast.lets.map { it.name },
                        params = ast.ast.params.map { it.name },
                        imports = emptyList(),
                    )
                is ParsedFile.PortfolioFile ->
                    EMPTY.copy(streams = ast.ast.streams, imports = ast.ast.imports.map { it.alias })
                null -> EMPTY
            }
    }
}
