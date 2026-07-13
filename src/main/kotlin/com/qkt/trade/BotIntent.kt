package com.qkt.trade

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * A one-shot trade request captured from `qkt bot buy/sell` arguments, before it is
 * rendered to canonical DSL. Exactly one sizing source is required: [lots] (raw broker
 * lots) or [sizingDsl] (a DSL sizing clause rendered verbatim, e.g. `"2 % OF EQUITY"`).
 *
 * Entry shape: market when no price is set; [limitPrice] alone is a limit entry;
 * [stopPrice] alone is a stop entry; [stopPrice] plus [stopLimitPrice] is a stop-limit.
 */
data class BotIntent(
    val side: Side,
    /** Broker-prefixed symbol, e.g. `"EXNESS:XAUUSD"`. */
    val qktSymbol: String,
    val lots: BigDecimal? = null,
    val sizingDsl: String? = null,
    val limitPrice: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    /** Limit leg of a stop-limit entry; requires [stopPrice]. */
    val stopLimitPrice: BigDecimal? = null,
    val sl: ExitSpec? = null,
    val tp: ExitSpec? = null,
    val tif: BotTif = BotTif.GTC,
    /** UTC epoch millis deadline; required iff [tif] is [BotTif.GTD]. */
    val expiresAtMs: Long? = null,
) {
    init {
        require((lots != null) xor (sizingDsl != null)) {
            "exactly one sizing source required: lots or --sizing"
        }
        lots?.let { require(it.signum() > 0) { "lots must be > 0: $it" } }
        require(limitPrice == null || stopPrice == null) {
            "entry cannot be both limit and stop"
        }
        require(stopLimitPrice == null || stopPrice != null) {
            "stop-limit entry requires a stop trigger price"
        }
        require((tif == BotTif.GTD) == (expiresAtMs != null)) {
            "GTD requires --expires and --expires requires TIF GTD"
        }
    }
}

/** Time-in-force choices the one-shot CLI supports. IOC/FOK are rejected at arg parse. */
enum class BotTif { GTC, DAY, GTD }

/**
 * A stop-loss or take-profit price form, mirroring the DSL bracket child-price grammar.
 * CLI syntax: `2610` or `at:2610` (absolute), `by:30` (distance from entry in price
 * units), `pct:1.5` (percent of entry), `rr:2` (take-profit only, multiple of the
 * stop distance).
 */
sealed interface ExitSpec {
    data class At(
        val price: BigDecimal,
    ) : ExitSpec

    data class By(
        val distance: BigDecimal,
    ) : ExitSpec

    data class Pct(
        val percent: BigDecimal,
    ) : ExitSpec

    data class Rr(
        val multiplier: BigDecimal,
    ) : ExitSpec

    companion object {
        /** Parses the CLI form. [allowRr] is false for stop-loss specs (`rr:` is TP-only). */
        fun parse(
            raw: String,
            allowRr: Boolean,
        ): ExitSpec {
            val (kind, value) =
                if (":" in raw) {
                    raw.substringBefore(':') to raw.substringAfter(':')
                } else {
                    "at" to raw
                }
            val num =
                value.toBigDecimalOrNull()
                    ?: error("invalid exit spec '$raw': '$value' is not a number")
            return when (kind.lowercase()) {
                "at" -> At(num)
                "by" -> By(num)
                "pct" -> Pct(num)
                "rr" -> {
                    require(allowRr) { "RR is take-profit only; use at:/by:/pct: for --sl" }
                    Rr(num)
                }
                else -> error("invalid exit spec '$raw': expected at:/by:/pct:/rr:")
            }
        }
    }
}
