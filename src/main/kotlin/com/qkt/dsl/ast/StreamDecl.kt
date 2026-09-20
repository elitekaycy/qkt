package com.qkt.dsl.ast

/**
 * The venue token a hub-backed stream declares: `alias = HUB:<dataset>[.<scope>] EVERY <tf>`.
 *
 * Named once so the parser, the compiler's read-only check and the market-source routing cannot
 * drift apart -- three copies of the string "HUB" is exactly how a fourth place gets missed.
 */
const val HUB_BROKER: String = "HUB"

/** Well-known broker/symbol identity used for synthetic DSL series streams. */
object SeriesSymbols {
    const val BROKER: String = "SERIES"
    const val ACCOUNT_EQUITY_SYMBOL: String = "ACCOUNT_EQUITY"
}

/** Synthetic series source exposed as a read-only stream alias. */
enum class SeriesSource(
    val broker: String,
    val symbol: String,
) {
    /** Account-level equity from the runtime's single equity tracker. */
    ACCOUNT_EQUITY(SeriesSymbols.BROKER, SeriesSymbols.ACCOUNT_EQUITY_SYMBOL),
}

/** Declaration for read-only synthetic series such as `SERIES ACCOUNT.EQUITY EVERY 1h`. */
data class SeriesDecl(
    val alias: String,
    val source: SeriesSource,
    val timeframe: String,
) {
    init {
        require(alias.isNotBlank()) { "SeriesDecl.alias must not be blank" }
        require(timeframe.isNotBlank()) { "SeriesDecl.timeframe must not be blank" }
    }
}

/**
 * A market stream the strategy reads: `alias = BROKER:SYMBOL EVERY <timeframe>`, with an
 * optional explicit warmup in bars.
 */
data class StreamDecl(
    val alias: String,
    val broker: String,
    val symbol: String,
    val timeframe: String,
    val warmupBars: Int? = null,
) {
    init {
        require(alias.isNotBlank()) { "StreamDecl.alias must not be blank" }
        require(broker.isNotBlank()) { "StreamDecl.broker must not be blank" }
        require(symbol.isNotBlank()) { "StreamDecl.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "StreamDecl.timeframe must not be blank" }
        if (warmupBars != null) require(warmupBars > 0) { "StreamDecl.warmupBars must be > 0 if set: $warmupBars" }
    }

    val qktSymbol: String get() = "$broker:$symbol"
}

/**
 * One declared sync group inside a `SYMBOLS` block. The engine evaluates the
 * strategy once per group-bar-window, with every member's bar in scope atomically.
 *
 * e.g. `SYNCHRONIZE gold silver WITHIN 200ms` parses to
 * `SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = 200)`.
 *
 * See `docs/superpowers/specs/2026-05-30-phase35-bar-sync-design.md` (#45).
 */
data class SyncGroupDecl(
    val aliases: List<String>,
    val timeoutMs: Long? = null,
) {
    init {
        require(aliases.size >= 2) {
            "SyncGroupDecl needs at least 2 aliases, got ${aliases.size}"
        }
        require(timeoutMs == null || timeoutMs > 0) {
            "SyncGroupDecl.timeoutMs must be positive when present: $timeoutMs"
        }
        require(aliases.toSet().size == aliases.size) {
            "SyncGroupDecl aliases must be unique: $aliases"
        }
    }
}
