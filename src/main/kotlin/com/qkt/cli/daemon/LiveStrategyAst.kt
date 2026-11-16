package com.qkt.cli.daemon

import com.qkt.cli.SymbolSetResolution
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.sets.SetCatalog
import java.nio.file.Path

/**
 * [file]'s strategy as a live session runs it: parsed, then its filtered symbol sets resolved once from [symbolSets]
 * (the venue listings) as of now, the way a backtest resolves them from its instruments file as of `--from`. Without
 * a catalog the sets stay, and the compiler refuses them by name.
 */
internal fun liveStrategyAst(
    file: Path,
    symbolSets: SetCatalog?,
): StrategyAst {
    val ast =
        when (val parsed = Dsl.parseFile(file)) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Failure -> {
                val msg = parsed.errors.joinToString("\n") { e -> "${e.line}:${e.col} — ${e.message}" }
                error("parse failure for $file:\n$msg")
            }
        }
    return symbolSets?.let { SymbolSetResolution.resolve(ast, it, System.currentTimeMillis(), false) } ?: ast
}
