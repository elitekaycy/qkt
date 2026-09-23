package com.qkt.broker

/**
 * Whether the venue behind [symbol] receives a bracket's pre-fill stop and target with its entry
 * and refuses the order when either is on the wrong side of the entry price — the MT5 attach path
 * ([OrderTypeCapability.BRACKET]) or a simulator standing in for it. Submit-time validation then
 * judges a `BY`/`PCT`/`RR` target's placeholder exactly as an absolute level. Read through
 * [Broker.capabilitiesFor], so routing brokers forward it per symbol.
 */
fun Broker.validatesSubmittedProtection(symbol: String): Boolean =
    capabilitiesFor(symbol).let {
        OrderTypeCapability.BRACKET in it || OrderTypeCapability.VALIDATES_SUBMITTED_PROTECTION in it
    }
