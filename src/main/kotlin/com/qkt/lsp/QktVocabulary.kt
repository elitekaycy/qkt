package com.qkt.lsp

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.stdlib.Constants
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry

/**
 * The single seam between the language server and qkt's front-end: every name a strategy
 * author can type, pulled live from [DslVocabulary] and the stdlib registries so the editor's
 * suggestions can never drift from what the parser and compiler actually accept.
 *
 * Casing matches how `.qkt` files are written in practice: keywords and operator words
 * upper case (`WHEN`, `CROSSES`), indicators and functions lower case (`ema`, `abs`),
 * constants upper case (`ONE_PERCENT`). The lexer matches keywords and indicator names
 * case-insensitively, so the casing here is convention, not correctness.
 */
object QktVocabulary {
    /**
     * Scalar functions plus the call-shaped names the parser recognizes itself: the clock
     * predicates (`calendar_window`, ...) and the rolling shorthands (`avg`, `count`, `mean`, `sum`).
     */
    val functions: List<String> =
        (FuncRegistry.names() + DslVocabulary.clockPredicates + DslVocabulary.rollingShorthands)
            .map { it.lowercase() }
            .distinct()
            .sorted()

    /**
     * Section and operator keywords, e.g. `STRATEGY`, `WHEN`, `CROSSES`. A spelling that is also a
     * function (`LOG`, `FLOOR`, `MIN`, `MAX`, `MEAN`, `SUM`) is offered once, as that function.
     */
    val keywords: List<String> = DslVocabulary.keywords.filter { it.lowercase() !in functions }

    /** Indicator names a strategy can call, e.g. `ema`, `rsi`, `atr`, including `resid` and `confirm_ratio`. */
    val indicators: List<String> =
        (IndicatorRegistry.names() + DslVocabulary.externalIndicators).map { it.lowercase() }.sorted()

    /** Named percentage constants, e.g. `ONE_PERCENT`, `BPS`. */
    val constants: List<String> = Constants.names().sorted()

    /** Per-bar and instrument fields readable off a venue stream alias, e.g. `btc.close`, `btc.tick_size`. */
    val streamFields: List<String> = (DslVocabulary.candleFields + DslVocabulary.metaFields).sorted()

    /** The fields a basket or series alias exposes: candle fields only, no instrument, contract, mark, option or flow fields. */
    val syntheticStreamFields: List<String> =
        (
            DslVocabulary.candleFields -
                (
                    DslVocabulary.contractFields + DslVocabulary.markFields + DslVocabulary.optionFields +
                        DslVocabulary.flowFields
                ).toSet()
        ).sorted()

    /** `candle` and `tick`, the whole-series selectors an indicator argument accepts after an alias. */
    val seriesSelectors: List<String> = DslVocabulary.seriesSelectors.sorted()
}
