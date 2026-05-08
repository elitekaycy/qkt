package com.qkt.dsl.compile

data class HubKey(
    val broker: String,
    val symbol: String,
    val timeframe: String,
) {
    init {
        require(broker.isNotBlank()) { "HubKey.broker must not be blank" }
        require(symbol.isNotBlank()) { "HubKey.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "HubKey.timeframe must not be blank" }
    }
}
