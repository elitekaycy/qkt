package com.qkt.dsl.ast

/**
 * How a [BasketDecl] combines its constituents into one composite price.
 *
 * Sealed so the compiler can exhaust every weighting mode. Only [EqualWeight] exists
 * today; the type reserves space for a future beta-weighted mode without a breaking
 * grammar change.
 */
sealed interface BasketWeighting {
    /**
     * Every constituent contributes equally to the composite's return — the basket is an
     * equal-weight log-return index (each bar's index return is the simple average of the
     * constituents' log returns). "Equal" means equal economic co-movement, not equal lots.
     */
    data object EqualWeight : BasketWeighting
}

/**
 * A named synthetic stream that combines two or more real streams into one composite,
 * tradeable as a single pseudo-symbol. Declared in the `SYMBOLS` block after the streams
 * it references.
 *
 * e.g. `antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h` declares
 * `BasketDecl(alias = "antipodean", weighting = EqualWeight, constituents = ["aud", "nzd"],
 * timeframe = "1h")`. Downstream, `antipodean.close` reads the composite index and
 * `BUY antipodean` fans out one order per constituent.
 *
 * @property alias the name the strategy refers to the basket by (`antipodean`).
 * @property weighting how constituents combine — [BasketWeighting.EqualWeight] today.
 * @property constituents the aliases of the real streams in the basket; at least two,
 *   distinct. Each must be a declared stream at the same [timeframe].
 * @property timeframe the candle window the basket aligns on (`1h`); must match every
 *   constituent's timeframe.
 */
data class BasketDecl(
    val alias: String,
    val weighting: BasketWeighting,
    val constituents: List<String>,
    val timeframe: String,
) {
    init {
        require(alias.isNotBlank()) { "BasketDecl.alias must not be blank" }
        require(constituents.size >= 2) {
            "BasketDecl '$alias' needs at least 2 constituents, got ${constituents.size}"
        }
        require(constituents.toSet().size == constituents.size) {
            "BasketDecl '$alias' constituents must be distinct: $constituents"
        }
        require(timeframe.isNotBlank()) { "BasketDecl.timeframe must not be blank" }
    }
}
