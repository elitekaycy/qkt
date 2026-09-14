package com.qkt.broker

/**
 * How a simulated venue prices a protective take-profit (a Close-intent limit) that is
 * crossed by a gap (#1135).
 */
enum class TakeProfitFill(
    val id: String,
) {
    /** The crossing print, or the level if better — credits gap-through improvement. */
    PRINT("print"),

    /** Exactly the take-profit level, as retail MT5 executes a crossed TP. */
    LEVEL("level"),
    ;

    companion object {
        fun fromConfig(raw: String): TakeProfitFill =
            when (raw.trim().lowercase()) {
                "print", "touch" -> PRINT
                "level" -> LEVEL
                else -> error("unknown take-profit fill mode '$raw' (valid: print, level)")
            }
    }
}
