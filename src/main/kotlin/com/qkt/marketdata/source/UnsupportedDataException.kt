package com.qkt.marketdata.source

class UnsupportedDataException(
    capability: MarketSourceCapability,
    providerClass: String,
) : RuntimeException("$providerClass does not support $capability")
