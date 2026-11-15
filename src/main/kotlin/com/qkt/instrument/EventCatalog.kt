package com.qkt.instrument

import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** An event contract's strike as the catalog stores it: the [type] in lower case and its bounds as decimal strings. */
@Serializable
data class CatalogStrike(
    val type: String,
    val floor: String? = null,
    val cap: String? = null,
) {
    /** The strike as the engine reads it; fails when the type is unknown or its bounds do not fit it. */
    fun predicate(): StrikePredicate {
        val strike =
            StrikeType.entries.firstOrNull { it.name.lowercase() == type }
                ?: throw IllegalArgumentException(
                    "strike type '$type' is not one of ${StrikeType.entries.joinToString { it.name.lowercase() }}",
                )
        return StrikePredicate(strike, floor?.let(::BigDecimal), cap?.let(::BigDecimal))
    }
}

/** An event contract's fee as the catalog stores it: the [ProbabilityFee] rates and rounding as decimal strings. */
@Serializable
data class CatalogFee(
    val taker: String,
    val maker: String? = null,
    val rounding: String,
) {
    /** The fee as the engine reads it. */
    fun fee(): ProbabilityFee = ProbabilityFee(BigDecimal(taker), maker?.let(::BigDecimal), BigDecimal(rounding))
}

/**
 * One contract of an event family as the event catalog records it: its venue [code] (`KXINXU-26NOV06H1500-T7425`), the
 * [event] it belongs to, its [predicate], its times in UTC epoch millis, the descriptive fields of [EventTerms], its
 * [fee] when known and its settled [outcome] (a decimal string in [0, 1]) once the venue has resolved it; null until
 * then. A row is valid exactly when it makes valid [EventTerms].
 */
@Serializable
data class ListedEvent(
    val code: String,
    val event: String,
    val predicate: CatalogStrike,
    val openAtMs: Long,
    val closeAtMs: Long,
    val expiryMs: Long,
    val occursAtMs: Long? = null,
    val occasion: String? = null,
    val canCloseEarly: Boolean = false,
    val exclusive: Boolean = false,
    val frequency: String? = null,
    val title: String? = null,
    val fee: CatalogFee? = null,
    val outcome: String? = null,
) {
    /** The qkt symbol of this contract under the family [root]: the venue prefix and the code with `-` as `_`. */
    fun qktSymbol(root: String): String = "${root.substringBefore(':')}:${code.replace('-', '_')}"

    /** The outcome as a number, or null before the contract has resolved. */
    fun outcomeOrNull(): BigDecimal? = outcome?.let(::BigDecimal)

    /** This contract's [EventTerms] in the family [root]; fails naming the code when the row is not valid terms. */
    fun terms(root: String): EventTerms =
        try {
            require(expiryMs >= closeAtMs) { "expiryMs must be >= closeAtMs: $expiryMs < $closeAtMs" }
            EventTerms(
                root = root,
                event = event,
                predicate = predicate.predicate(),
                openAtMs = openAtMs,
                closeAtMs = closeAtMs,
                expiryMs = expiryMs,
                occursAtMs = occursAtMs,
                occasion = occasion,
                canCloseEarly = canCloseEarly,
                exclusive = exclusive,
                frequency = frequency,
                title = title,
                fee = fee?.fee(),
                outcome = outcomeOrNull(),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("event contract '$code' of $root: ${e.message}", e)
        }
}

/**
 * Every known contract of one event family ([root], `VENUE:FAMILY`): the family's point-in-time membership, the
 * backtest's metadata for its contracts and the settlement source of their expiries. Written by
 * `qkt data import --kind events`; read by [EventCatalogRegistry].
 */
@Serializable
data class EventCatalog(
    val root: String,
    val contracts: List<ListedEvent>,
) {
    init {
        val duplicate = contracts.groupBy { it.code }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "EventCatalog $root: duplicate contract '${duplicate?.key}'" }
        for (contract in contracts) {
            QktSymbols.requireFileSafe(contract.qktSymbol(root))
            contract.terms(root)
        }
    }

    /** The same catalog in expiry order (then code); the form stored. */
    fun sorted(): EventCatalog = copy(contracts = contracts.sortedWith(compareBy({ it.expiryMs }, { it.code })))

    /**
     * This catalog with [rows] merged in by code: a new code is added, a known one is replaced by the row, and a row
     * without an outcome keeps the outcome already recorded. A row whose outcome contradicts a recorded one fails, so a
     * re-import never rewrites a settled result silently.
     */
    fun merge(rows: List<ListedEvent>): EventCatalog {
        val byCode = LinkedHashMap(contracts.associateBy { it.code })
        for (row in rows) {
            val known = byCode[row.code]
            val recorded = known?.outcomeOrNull()
            val incoming = row.outcomeOrNull()
            require(recorded == null || incoming == null || recorded.compareTo(incoming) == 0) {
                "event contract '${row.code}' of $root already resolved at ${known?.outcome}; " +
                    "the import says ${row.outcome}"
            }
            byCode[row.code] = if (incoming == null && known != null) row.copy(outcome = known.outcome) else row
        }
        return EventCatalog(root, byCode.values.toList()).sorted()
    }
}
