package com.qkt.dsl.compile

/**
 * Identity triple for a stream managed by [CandleHub].
 *
 * `broker` is the venue prefix (`EXNESS`, `BYBIT_SPOT`, `BACKTEST`), `symbol` is the
 * instrument, `timeframe` is the candle window (`1m`, `15m`, `1h`, ...). Two strategies
 * targeting the same triple share aggregation; differing on any field gets its own slot.
 */
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
