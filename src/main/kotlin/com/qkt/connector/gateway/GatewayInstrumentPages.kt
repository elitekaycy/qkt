package com.qkt.connector.gateway

import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A code that left the gateway's listing (`removed` of an `updated_since` read). */
@Serializable
data class WireRemoved(
    val code: String,
)

/**
 * One page of a filtered `GET /v1/instruments` read: its [instruments], the codes [removed] since an `updated_since`
 * (absent otherwise), the cursor of the [next] page (absent on the last), and when the gateway read the listing
 * served ([asOf]), the `updated_since` of the next poll for changes.
 */
@Serializable
data class WireInstrumentPage(
    val instruments: List<WireInstrument>,
    val removed: List<WireRemoved> = emptyList(),
    val next: String? = null,
    @SerialName("as_of") val asOf: Long,
)

/**
 * The gateway's instruments matching [filters] (`kind`, `category`, `series`, `event`, `status`, `updated_since`),
 * every page of them, as one page: instruments and removals in page order, and the first page's `as_of`, so a poll
 * from it may see a change twice but never misses one. With [filters] the gateway always answers in pages.
 */
internal fun GatewayClient.instrumentPages(filters: List<Pair<String, String>>): WireInstrumentPage {
    val pages = mutableListOf<WireInstrumentPage>()
    var cursor: String? = null
    do {
        val params = filters + listOfNotNull(cursor?.let { "cursor" to it })
        val query = params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val page = read("/v1/instruments?$query", WireInstrumentPage.serializer())
        pages += page
        cursor = page.next
    } while (cursor != null)
    return WireInstrumentPage(
        instruments = pages.flatMap { it.instruments },
        removed = pages.flatMap { it.removed },
        asOf = pages.first().asOf,
    )
}
