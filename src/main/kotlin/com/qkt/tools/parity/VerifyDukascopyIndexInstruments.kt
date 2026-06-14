package com.qkt.tools.parity

import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import com.qkt.marketdata.store.dukascopy.DukascopyTickDecoder
import com.qkt.marketdata.store.dukascopy.OkHttpHourDownloader
import java.time.LocalDate

/**
 * Sanity-checks that each newly-mapped index decodes to a price in its expected band, confirming
 * the datafeed token and divisor are correct. A wrong divisor would surface here as a price off
 * by 10-100x (e.g. DXY reading 1038 or 10 instead of ~104) before it could silently corrupt PnL.
 *
 * Network tool (hits the dukascopy CDN), not a unit test. A Tuesday in the US session is a safe
 * hour. e.g. on 2024-03-05 14:00 UTC, SPX decodes to ~5100 and DXY to ~104.
 */
fun main() {
    val downloader = OkHttpHourDownloader()
    val day = LocalDate.of(2024, 3, 5)
    val hour = 14
    val bands =
        mapOf(
            "DXY" to (90.0..120.0),
            "SPX" to (3000.0..7000.0),
            "NDX" to (10000.0..25000.0),
            "DJI" to (25000.0..50000.0),
            "RUT" to (1500.0..3000.0),
        )
    var ok = true
    for ((symbol, band) in bands) {
        val inst = DukascopyInstrument.of(symbol)
        val bi5 = downloader.download(inst.dukascopyName, day, hour)
        if (bi5 == null) {
            println("FAIL $symbol (${inst.dukascopyName}): no hour file")
            ok = false
            continue
        }
        val ticks =
            DukascopyTickDecoder.decodeRecords(
                DukascopyTickDecoder.decompress(bi5),
                hourStartMs = 0L,
                divisor = inst.priceDivisor,
                symbol = symbol,
            )
        val mid = ticks.firstOrNull()?.price?.toDouble()
        val pass = mid != null && mid in band
        println(
            "${if (pass) "OK  " else "FAIL"} $symbol (${inst.dukascopyName}) mid=$mid expected=$band records=${ticks.size}",
        )
        if (!pass) ok = false
    }
    check(ok) { "one or more index instruments failed the price-band check" }
    println("all index instruments verified")
}
