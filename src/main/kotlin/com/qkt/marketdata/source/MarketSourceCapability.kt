package com.qkt.marketdata.source

enum class MarketSourceCapability {
    LIVE_TICKS,
    BARS,
    TICKS,

    /**
     * The feed supplies traded volume on its ticks/candles. Quote-driven venues (MT5 FX/metals)
     * do not; exchanges (crypto) do. Volume-weighted indicators (VWAP, OBV) require it — a strategy
     * binding one to a feed without this capability is rejected at deploy rather than silently
     * never becoming ready.
     */
    VOLUME,
}
