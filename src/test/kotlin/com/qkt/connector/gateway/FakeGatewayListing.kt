package com.qkt.connector.gateway

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse

private val listingJson = Json { encodeDefaults = true }

/** When a [FakeGateway] read its listing, as `as_of` reports it. */
internal const val LISTING_AS_OF = 1_794_000_000_000L

/**
 * `GET /v1/instruments` of a [FakeGateway]: the plain list without parameters; with any, a page of the instruments
 * whose `kind`, `category`, `series` and `event` equal the ones asked, `status=open` keeping those trading, after
 * `cursor`, [FakeGateway.listingPage] at a time. `updated_since` answers [FakeGateway.changes] when set.
 */
internal fun FakeGateway.listing(url: HttpUrl): MockResponse {
    val all = codes.map { instruments[it] ?: FakeWire.instrument(it) }
    if (url.querySize == 0) {
        return FakeWire.ok(listingJson.encodeToString(ListSerializer(WireInstrument.serializer()), all))
    }
    val since = url.queryParameter("updated_since")?.toLong()
    val hook = changes
    if (since != null && hook != null) return page(hook(since))
    val fields: Map<String, (WireInstrument) -> String?> =
        mapOf("kind" to { it.kind }, "category" to { it.category }, "series" to { it.series }, "event" to { it.event })
    val open = { i: WireInstrument -> i.trading != "disabled" && i.trading != "close_only" }
    val kept =
        all
            .filter { i -> fields.all { (name, field) -> url.queryParameter(name)?.let { it == field(i) } ?: true } }
            .filter { url.queryParameter("status") != "open" || open(it) }
            .sortedBy { it.code }
            .filter { i -> url.queryParameter("cursor")?.let { i.code > it } ?: true }
    val page = kept.take(listingPage)
    val next = page.lastOrNull()?.code?.takeIf { kept.size > page.size }
    return page(WireInstrumentPage(page, next = next, asOf = LISTING_AS_OF))
}

private fun page(page: WireInstrumentPage) =
    FakeWire.ok(listingJson.encodeToString(WireInstrumentPage.serializer(), page))
