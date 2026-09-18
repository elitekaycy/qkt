package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst

/**
 * Compile-time checks for every `BASKET` declaration: each constituent must be a
 * declared real stream (not unbound and not itself a basket), and the basket's
 * timeframe must match each constituent's timeframe (so their bars share a window).
 *
 * e.g. `antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h` requires `aud` and
 * `nzd` to be declared `EVERY 1h` streams.
 */
internal fun validateBaskets(ast: StrategyAst) {
    if (ast.baskets.isEmpty()) return
    val streamTimeframes = ast.streams.associate { it.alias to it.timeframe }
    val basketAliases = ast.baskets.map { it.alias }.toSet()
    for (basket in ast.baskets) {
        for (constituent in basket.constituents) {
            require(constituent !in basketAliases) {
                "BASKET '${basket.alias}' constituent '$constituent' is itself a basket; " +
                    "baskets of baskets are not supported."
            }
            val constituentTf = streamTimeframes[constituent]
            require(constituentTf != null) {
                "BASKET '${basket.alias}' constituent '$constituent' is not a declared " +
                    "stream in SYMBOLS."
            }
            require(constituentTf == basket.timeframe) {
                "BASKET '${basket.alias}' timeframe '${basket.timeframe}' does not match " +
                    "constituent '$constituent' timeframe '$constituentTf'."
            }
        }
    }
}
