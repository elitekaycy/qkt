package com.qkt.marketdata.store.binance

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Binance's free public market data: daily/monthly zip files on `data.binance.vision`, the S3
 * listing of those files, and the delivery-price endpoint of the futures API. No API key.
 */
class BinanceVisionClient(
    private val filesBaseUrl: String = FILES_BASE,
    private val listingBaseUrl: String = LISTING_BASE,
    private val apiBaseUrl: String = API_BASE,
    private val http: OkHttpClient = OkHttpClient(),
) {
    /** The bytes at [path] under the files base, or null when the file does not exist. */
    fun download(path: String): ByteArray? =
        http.newCall(Request.Builder().url("$filesBaseUrl/$path").build()).execute().use { resp ->
            when {
                resp.code == HTTP_NOT_FOUND -> null
                resp.isSuccessful -> resp.body?.bytes() ?: error("empty body for $path")
                else -> error("HTTP ${resp.code} downloading $path")
            }
        }

    /** Every common prefix directly under [prefix] (delimiter `/`), following truncated pages. */
    fun listPrefixes(prefix: String): List<String> {
        val found = mutableListOf<String>()
        var marker: String? = null
        do {
            val url =
                listingBaseUrl
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("prefix", prefix)
                    .addQueryParameter("delimiter", "/")
                    .apply { marker?.let { addQueryParameter("marker", it) } }
                    .build()
            val xml =
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code} listing $prefix" }
                    resp.body?.string() ?: error("empty listing for $prefix")
                }
            val page =
                PREFIX
                    .findAll(xml)
                    .map { it.groupValues[1] }
                    .filter { it != prefix }
                    .toList()
            found += page
            val truncated = TRUNCATED.find(xml)?.groupValues?.get(1) == "true"
            val previous = marker
            marker =
                if (truncated) {
                    NEXT_MARKER.find(xml)?.groupValues?.get(1) ?: page.lastOrNull()
                        ?: error("listing for $prefix is truncated but gives no marker to continue from")
                } else {
                    null
                }
            check(
                marker == null || marker != previous,
            ) { "listing for $prefix is truncated but does not advance past $marker" }
        } while (marker != null)
        return found
    }

    /** Delivery prices of [pair]'s quarterly contracts: deliveryTime (UTC ms) to the exact price text. */
    fun deliveryPrices(pair: String): Map<Long, String> {
        val body =
            http
                .newCall(
                    Request.Builder().url("$apiBaseUrl/futures/data/delivery-price?pair=$pair").build(),
                ).execute()
                .use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code} reading delivery prices for $pair" }
                    resp.body?.string() ?: error("empty delivery-price body for $pair")
                }
        return Json.parseToJsonElement(body).jsonArray.associate { row ->
            val obj = row.jsonObject
            (obj.getValue("deliveryTime") as JsonPrimitive).content.toLong() to
                (obj.getValue("deliveryPrice") as JsonPrimitive).content
        }
    }

    /** The single entry of a Binance data zip as text, or null for an empty archive; refuses several entries. */
    fun unzipSingle(zip: ByteArray): String? =
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            if (input.nextEntry == null) return null
            val text = input.readBytes().toString(Charsets.UTF_8)
            var entries = 1
            while (input.nextEntry != null) entries++
            require(entries == 1) { "Binance archive has $entries entries, expected 1" }
            text
        }

    companion object {
        const val FILES_BASE: String = "https://data.binance.vision"
        const val LISTING_BASE: String = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision"
        const val API_BASE: String = "https://fapi.binance.com"
        private const val HTTP_NOT_FOUND = 404
        private val PREFIX = Regex("<Prefix>([^<]+)</Prefix>")
        private val TRUNCATED = Regex("<IsTruncated>(true|false)</IsTruncated>")
        private val NEXT_MARKER = Regex("<NextMarker>([^<]+)</NextMarker>")
    }
}
