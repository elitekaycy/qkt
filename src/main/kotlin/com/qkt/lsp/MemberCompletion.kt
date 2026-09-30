package com.qkt.lsp

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.parse.TokenKind
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Completion right after a `.`: resolves the dotted owner chain to the left of the cursor and
 * offers exactly what the parser accepts there. `POSITION.btc.` offers the position accessors,
 * `SEQUENCE.setup.` the sequence's state and stage names, `NOW.` / `ACCOUNT.` / `EXIT.` /
 * `STREAK.` / `TRADES.` / `COOLDOWN.` their member tables, and a stream, basket or series alias
 * its fields plus the `candle` / `tick` series selectors. Members are [CompletionItemKind.Field];
 * aliases and other declared names are [CompletionItemKind.Variable].
 */
internal object MemberCompletion {
    /** Owners whose members follow directly: `NOW.hour_utc`, `ACCOUNT.equity`, ... */
    private val directOwners: Set<String> =
        setOf(
            TokenKind.NOW.name,
            TokenKind.ACCOUNT.name,
            TokenKind.EXIT.name,
            TokenKind.STREAK.name,
            TokenKind.TRADES.name,
            TokenKind.COOLDOWN.name,
        )

    /** Owners that take a stream or basket alias next: `POSITION.btc`, `OPEN_ORDERS.btc`. */
    private val aliasOwners: Set<String> =
        setOf(TokenKind.POSITION.name, TokenKind.POSITION_AVG_PRICE.name, TokenKind.OPEN_ORDERS.name)

    /**
     * The owner chain ending at [dotOffset] (the index of the `.` the cursor follows), outermost
     * first: for `SEQUENCE.setup.` it is `["SEQUENCE", "setup"]`. Empty when nothing precedes the dot.
     */
    fun ownerChain(
        text: String,
        dotOffset: Int,
    ): List<String> {
        val chain = ArrayDeque<String>()
        var end = dotOffset + 1
        while (end > 0 && text[end - 1] == '.') {
            val start = Cursor.identStart(text, end - 1)
            if (start == end - 1) break
            chain.addFirst(text.substring(start, end - 1))
            end = start
        }
        return chain.toList()
    }

    fun items(
        chain: List<String>,
        symbols: DocumentSymbols,
    ): List<CompletionItem> {
        if (chain.isEmpty()) return emptyList()
        val head = chain.first().uppercase()
        return when {
            head in directOwners && chain.size == 1 -> fields(DslVocabulary.members.getValue(head))
            head in aliasOwners && chain.size == 1 -> variables(symbols.positionAliases)
            head == TokenKind.POSITION.name && chain.size == 2 && chain[1] in symbols.positionAliases ->
                fields(DslVocabulary.members.getValue(head))
            head == TokenKind.SEQUENCE.name -> sequenceItems(chain, symbols)
            chain.size == 1 -> aliasItems(chain.first(), symbols)
            else -> emptyList()
        }
    }

    private fun sequenceItems(
        chain: List<String>,
        symbols: DocumentSymbols,
    ): List<CompletionItem> =
        when (chain.size) {
            1 -> variables(symbols.sequences.map { it.name })
            2 ->
                symbols.sequence(chain[1])?.let { seq ->
                    fields(DslVocabulary.sequenceMembers) + variables(seq.stages.map { it.name })
                } ?: emptyList()
            3 ->
                symbols
                    .sequence(chain[1])
                    ?.takeIf { seq -> seq.stages.any { it.name == chain[2] } }
                    ?.let { fields(DslVocabulary.sequenceStageMembers) }
                    ?: emptyList()
            else -> emptyList()
        }

    /** After `<alias>.`: the fields the alias kind exposes, plus the series selectors. */
    private fun aliasItems(
        alias: String,
        symbols: DocumentSymbols,
    ): List<CompletionItem> =
        when (alias) {
            in symbols.streamAliases -> fields(QktVocabulary.streamFields + QktVocabulary.seriesSelectors)
            in symbols.basketAliases, in symbols.seriesAliases ->
                fields(QktVocabulary.syntheticStreamFields + QktVocabulary.seriesSelectors)
            else -> emptyList()
        }

    private fun fields(names: List<String>): List<CompletionItem> = names.map { item(it, CompletionItemKind.Field) }

    private fun variables(names: List<String>): List<CompletionItem> =
        names.map { item(it, CompletionItemKind.Variable) }

    private fun item(
        label: String,
        kind: CompletionItemKind,
    ): CompletionItem = CompletionItem(label).apply { this.kind = kind }
}
