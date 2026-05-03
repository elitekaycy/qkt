package com.qkt.strategy

sealed class Signal {
    data class Buy(
        val symbol: String,
        val size: Double,
    ) : Signal()

    data class Sell(
        val symbol: String,
        val size: Double,
    ) : Signal()
}
