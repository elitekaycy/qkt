package com.qkt.execution

/** Why a position-closing fill occurred, for DSL exit-hook dispatch. */
enum class ExitReason {
    STOP,
    TAKE_PROFIT,
    CLOSE,
}
