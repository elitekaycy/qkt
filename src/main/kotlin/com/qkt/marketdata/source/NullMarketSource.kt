package com.qkt.marketdata.source

object NullMarketSource : MarketSource {
    override val name: String = "Null"
    override val capabilities: Set<MarketSourceCapability> = emptySet()

    override fun supports(symbol: String): Boolean = false
}
