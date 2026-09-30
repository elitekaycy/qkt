package com.qkt.lsp

import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.stdlib.Constants
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind

/**
 * Produces hover documentation for the identifier under a cursor.
 *
 * Resolution walks the qkt vocabulary in order — indicator, function, constant, keyword —
 * then falls back to the symbols this document declares (stream, basket and series aliases),
 * so hovering an alias shows where it comes from. Returns null when the cursor is not on a
 * word we have documentation for; the editor then shows nothing.
 */
object HoverProvider {
    fun hover(
        text: String,
        line: Int,
        character: Int,
        lastGoodAst: ParsedFile?,
    ): Hover? {
        val token = Cursor.identAround(text, Cursor.offset(text, line, character)) ?: return null
        val markdown = markdownFor(token, lastGoodAst) ?: return null
        return Hover(MarkupContent(MarkupKind.MARKDOWN, markdown))
    }

    private fun markdownFor(
        token: String,
        ast: ParsedFile?,
    ): String? {
        QktDocs.indicator(token)?.let { return it }
        QktDocs.function(token)?.let { return it }
        Constants.byName(token.uppercase())?.let { return "```qkt\n$token = $it\n```\nNamed constant." }
        QktDocs.keyword(token)?.let { return it }
        return aliasMarkdown(token, DocumentSymbols.of(ast))
    }

    private fun aliasMarkdown(
        token: String,
        symbols: DocumentSymbols,
    ): String? {
        symbols.streams.firstOrNull { it.alias == token }?.let {
            return "```qkt\n${it.alias} = ${it.qktSymbol} EVERY ${it.timeframe}\n```\nStream alias."
        }
        symbols.baskets.firstOrNull { it.alias == token }?.let {
            val members = it.constituents.joinToString(", ")
            return "```qkt\n${it.alias} = BASKET EQUAL_WEIGHT [$members] EVERY ${it.timeframe}\n```\nBasket alias."
        }
        symbols.series.firstOrNull { it.alias == token }?.let {
            return "```qkt\n${it.alias} = SERIES ${it.source.name.replace('_', '.')} EVERY ${it.timeframe}\n```\n" +
                "Series alias."
        }
        return null
    }
}
