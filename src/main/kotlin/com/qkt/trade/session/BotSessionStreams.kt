package com.qkt.trade.session

import com.qkt.candles.TimeWindow

/**
 * The series a bot session declares: every symbol at every timeframe, each a DSL stream alias
 * `s<i>_<tf>` (`s0_5m`). The smallest timeframe is the session's base: an intent on a symbol acts on
 * its base series, and every other timeframe must be a whole multiple of it.
 */
class BotSessionStreams(
    val symbols: List<String>,
    val timeframes: List<String>,
) {
    init {
        require(symbols.isNotEmpty()) { "a session needs at least one symbol" }
        require(timeframes.isNotEmpty()) { "a session needs at least one timeframe" }
        symbols.forEach { require(it.contains(':')) { "symbol must be BROKER:SYMBOL, got '$it'" } }
        require(symbols.toSet().size == symbols.size) { "a symbol is listed twice: $symbols" }
    }

    private val windows = timeframes.associateWith { TimeWindow.parse(it) }

    /** The smallest timeframe. */
    val baseTf: String = timeframes.minBy { windows.getValue(it).durationMs }

    val baseWindow: TimeWindow = windows.getValue(baseTf)

    init {
        timeframes.forEach { tf ->
            require(windows.getValue(tf).durationMs % baseWindow.durationMs == 0L) {
                "timeframe $tf is not a whole multiple of the session's base $baseTf"
            }
        }
    }

    /** The alias of [symbol] at [tf], or null when the session does not declare that series. */
    fun alias(
        symbol: String,
        tf: String = baseTf,
    ): String? {
        val i = symbols.indexOf(symbol)
        return if (i < 0 || tf !in windows) null else "s${i}_${tf.lowercase()}"
    }

    /** The series key `<symbol>@<tf>` of [alias]. */
    fun seriesOf(alias: String): String? =
        symbols.indices
            .flatMap { i -> timeframes.map { tf -> Triple(i, tf, "s${i}_${tf.lowercase()}") } }
            .firstOrNull { it.third == alias }
            ?.let { (i, tf, _) -> "${symbols[i]}@$tf" }

    /** The never-firing strategy [name] that declares every series, so the engine builds each. */
    fun source(name: String): String {
        val streams =
            symbols.indices.flatMap { i ->
                // Base series first: the engine's candle window is the first stream's.
                timeframes.sortedBy { windows.getValue(it).durationMs }.map { tf ->
                    "    ${alias(symbols[i], tf)} = ${symbols[i]} EVERY $tf"
                }
            }
        return (
            listOf("STRATEGY $name VERSION 1", "", "SYMBOLS") + streams +
                listOf("", "RULES", "    WHEN 1 > 2", "    THEN BUY ${alias(symbols[0])} SIZING 0.01", "")
        ).joinToString("\n")
    }

    /** Declared series as "`symbol@tf`", for error messages. */
    fun describe(): String = symbols.flatMap { s -> timeframes.map { "$s@$it" } }.joinToString(", ")
}
