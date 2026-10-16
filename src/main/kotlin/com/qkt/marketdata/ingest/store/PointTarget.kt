package com.qkt.marketdata.ingest.store

import com.qkt.instrument.FundingRate
import com.qkt.instrument.FundingRateStore
import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.OpenInterestRecord
import com.qkt.marketdata.openinterest.OpenInterest
import com.qkt.marketdata.openinterest.OpenInterestStore
import java.math.BigDecimal
import java.nio.file.Path

/**
 * Writes imported funding rates or open interest into the one-file-per-contract stores qkt reads
 * ([FundingRateStore], [OpenInterestStore]). Those stores replace a stored value at the same time without
 * a word, so [conflicts] lists every time where the store already holds a different value: the import
 * refuses on them unless told to replace.
 */
class PointTarget(
    root: Path,
    private val qktSymbol: String,
    private val funding: Boolean,
) {
    private val fundingStore = FundingRateStore(root)
    private val oiStore = OpenInterestStore(root)

    /** The venue directory holding the contract's file and the provenance. */
    val dir: Path = (if (funding) fundingStore.path(qktSymbol) else oiStore.path(qktSymbol)).parent

    /** The contract's name in the provenance (`BTCUSDT` for `BINANCE_UM:BTCUSDT`). */
    val key: String = qktSymbol.substringAfter(':')

    /** How many values are stored now. */
    fun storedCount(): Int =
        if (funding) {
            fundingStore.read(qktSymbol)?.size ?: 0
        } else {
            oiStore.read(qktSymbol)?.size
                ?: 0
        }

    /** Times among [records] where the store holds a different value. */
    fun conflicts(records: List<IngestRecord>): List<Long> =
        if (funding) {
            val stored = fundingStore.read(qktSymbol).orEmpty().associateBy { it.timeMs }
            records
                .filterIsInstance<FundingRecord>()
                .filter { r ->
                    stored[r.timeMs]?.let {
                        !same(it.rate, r.rate) ||
                            !same(it.price, r.price)
                    } ==
                        true
                }.map { it.timeMs }
        } else {
            val stored = oiStore.read(qktSymbol).orEmpty().associateBy { it.timeMs }
            records
                .filterIsInstance<OpenInterestRecord>()
                .filter { r ->
                    stored[r.timeMs]?.let { !same(it.openInterest, r.openInterest) } ==
                        true
                }.map { it.timeMs }
        }

    /** Merges [records] into the store; returns how many values it now holds. */
    fun write(records: List<IngestRecord>): Int =
        if (funding) {
            fundingStore.merge(qktSymbol, records.filterIsInstance<FundingRecord>().map(::rate))
        } else {
            oiStore.merge(qktSymbol, records.filterIsInstance<OpenInterestRecord>().map(::oi))
        }

    /** Reads the stored values back, for post-write verification: (count, first time, last time). */
    fun readBack(): Triple<Int, Long?, Long?> {
        val times =
            if (funding) {
                fundingStore.read(qktSymbol).orEmpty().map {
                    it.timeMs
                }
            } else {
                oiStore.read(qktSymbol).orEmpty().map { it.timeMs }
            }
        return Triple(times.size, times.minOrNull(), times.maxOrNull())
    }

    private fun rate(r: FundingRecord) = FundingRate(r.timeMs, r.rate, r.price)

    private fun oi(r: OpenInterestRecord) = OpenInterest(r.timeMs, r.openInterest)

    private fun same(
        a: BigDecimal?,
        b: BigDecimal?,
    ): Boolean = if (a == null || b == null) a == b else a.compareTo(b) == 0
}
