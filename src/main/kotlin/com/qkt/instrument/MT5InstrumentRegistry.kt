package com.qkt.instrument

import com.qkt.broker.mt5.MT5Broker

/**
 * [InstrumentRegistry] adapter over [MT5Broker]. Used by live mode where the trading
 * pipeline already constructed an MT5 broker — meta comes from the broker's own
 * `/symbol_info` cache, so live live runs never read a YAML manifest.
 *
 * Multi-broker setups (when qkt grows to route some symbols through Bybit, etc.) compose
 * registries via [CompositeInstrumentRegistry], where each per-broker impl handles its
 * own venue's symbols and lookups fall through.
 */
class MT5InstrumentRegistry(
    private val broker: MT5Broker,
) : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? = broker.instrumentMeta(qktSymbol)
}
