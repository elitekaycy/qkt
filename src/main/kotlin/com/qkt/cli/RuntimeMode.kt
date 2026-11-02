package com.qkt.cli

/** `runtime.mode`: how much production governance a run gets. */
enum class RuntimeMode {
    DEV,
    PAPER,
    PRODUCTION,
    ;

    val production: Boolean get() = this == PRODUCTION

    companion object {
        fun fromConfig(raw: String?): RuntimeMode =
            when (raw?.trim()?.lowercase()) {
                null, "", "dev" -> DEV
                "paper" -> PAPER
                "production" -> PRODUCTION
                else -> error("runtime.mode must be one of: dev, paper, production (got '$raw')")
            }
    }
}
