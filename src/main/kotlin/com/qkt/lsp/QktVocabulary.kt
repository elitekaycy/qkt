package com.qkt.lsp

import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.stdlib.Constants
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry

/**
 * The single seam between the language server and qkt's front-end: every name a strategy
 * author can type, pulled live from the lexer keyword table and the stdlib registries so
 * the editor's suggestions can never drift from what the parser actually accepts.
 *
 * Casing matches how `.qkt` files are written in practice: keywords and operator words
 * upper case (`WHEN`, `CROSSES`), indicators and functions lower case (`ema`, `abs`),
 * constants upper case (`ONE_PERCENT`). The lexer matches keywords and indicator names
 * case-insensitively, so the casing here is convention, not correctness.
 */
object QktVocabulary {
    /** Section and operator keywords, e.g. `STRATEGY`, `WHEN`, `CROSSES`. */
    val keywords: List<String> = Lexer.keywordSpellings().sorted()

    /** Indicator names a strategy can call, e.g. `ema`, `rsi`, `atr`. */
    val indicators: List<String> = IndicatorRegistry.names().map { it.lowercase() }.sorted()

    /**
     * Scalar functions, e.g. `abs`, `sqrt`, `min`. `calendar_window` is a parser-recognized
     * boolean primitive that lives outside [FuncRegistry] (it reads the clock), so it is added
     * explicitly here.
     */
    val functions: List<String> = (FuncRegistry.names().map { it.lowercase() } + "calendar_window").sorted()

    /** Named percentage constants, e.g. `ONE_PERCENT`, `BPS`. */
    val constants: List<String> = Constants.names().sorted()

    /** Per-bar fields readable off a stream alias, e.g. `btc.close`, `btc.tick_size`. */
    val streamFields: List<String> = (ExprCompiler.CANDLE_FIELDS + ExprCompiler.META_FIELDS).sorted()
}
