package com.qkt.strategy

/** Whether the engine is running historical replay or against a live data source. */
enum class Mode {
    /** Historical replay — clock advances by tick timestamp, not wall time. */
    BACKTEST,

    /** Live or paper deployment — clock follows wall time. */
    LIVE,
}
