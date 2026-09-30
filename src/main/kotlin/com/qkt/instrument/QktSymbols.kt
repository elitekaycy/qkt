package com.qkt.instrument

/**
 * Rules for the `VENUE:SYMBOL` strings qkt keys positions, marks and state files by. State files
 * are named after the symbol, so a symbol must never contain a path separator or be a dot-name.
 */
object QktSymbols {
    private val SAFE = Regex("[A-Za-z0-9_]+:[A-Za-z0-9_.@-]+")

    /** Fails unless [qktSymbol] is venue-qualified and safe to use in a file name. */
    fun requireFileSafe(qktSymbol: String) {
        require(SAFE.matches(qktSymbol) && qktSymbol.substringAfter(':').any { it != '.' }) {
            "symbol '$qktSymbol' must be VENUE:SYMBOL using only letters, digits, '_', '.', '@' and '-'"
        }
    }
}
