package com.qkt.connector.gateway

import com.qkt.instrument.EquityCosts
import com.qkt.instrument.universe.VenueCatalog

/** The listed kinds a gateway listing describes fully ([instrumentSpec]), and so the kinds a live set can filter. */
internal val GATEWAY_CATALOG_KINDS: Set<String> = setOf("spot", "equity", "event")

/**
 * The listing as the catalog a live filtered symbol set ranks: a [kind] the listing describes is read through
 * [ofKind] (the gateway's `kind` filter), any other read is the [whole] listing.
 */
internal fun gatewayCatalog(
    symbols: GatewaySymbols,
    kind: String?,
    whole: () -> List<WireInstrument>,
    ofKind: (String) -> List<WireInstrument>,
): VenueCatalog = gatewayCatalog(symbols, kind?.takeIf { it in GATEWAY_CATALOG_KINDS }?.let(ofKind) ?: whole())

/**
 * [listed] as the catalog a live filtered symbol set ranks: each spot, equity and event instrument as its
 * [instrumentSpec] meta, an equity marked as one (no costs: the row is read, never traded) so `kind = equity` finds
 * it. A future or option is left out and counted: its exchange terms come from its contract catalog, not the listing.
 */
internal fun gatewayCatalog(
    symbols: GatewaySymbols,
    listed: List<WireInstrument>,
): VenueCatalog {
    val (described, other) = listed.partition { it.kind in GATEWAY_CATALOG_KINDS }
    val rows =
        described.mapNotNull { instrument ->
            instrument.instrumentSpec(symbols.qkt(instrument.code))?.meta?.let { meta ->
                if (instrument.kind == "equity") meta.copy(equityCosts = EquityCosts()) else meta
            }
        }
    val omitted =
        other
            .groupingBy { it.kind }
            .eachCount()
            .toSortedMap()
            .map { (kind, count) -> "$count $kind" }
            .takeIf { it.isNotEmpty() }
            ?.let { "left out: ${it.joinToString()} (a listing carries no exchange terms for them)" }
    return VenueCatalog(rows, omitted)
}
