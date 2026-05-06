package com.qkt.execution

enum class OrderState {
    CREATED,
    PENDING,
    SUBMITTED,
    WORKING,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
}

val OrderState.isTerminal: Boolean
    get() = this == OrderState.FILLED || this == OrderState.CANCELLED || this == OrderState.REJECTED
