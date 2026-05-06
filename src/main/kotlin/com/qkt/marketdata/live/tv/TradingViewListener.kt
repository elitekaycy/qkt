package com.qkt.marketdata.live.tv

interface TradingViewListener {
    fun onFrame(frame: TradingViewFrame)

    fun onConnected()

    fun onDisconnected(reason: String)
}
