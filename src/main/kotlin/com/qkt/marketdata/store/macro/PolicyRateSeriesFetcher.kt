package com.qkt.marketdata.store.macro

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import okhttp3.OkHttpClient

/**
 * Provisions RBA and RBNZ policy rates from their official XLSX statistical tables and writes the
 * point-in-time RBA-minus-RBNZ differential. HTTPS and operator-managed local copies are accepted;
 * the latter can be selected with [RBA_SOURCE_ENV] and [RBNZ_SOURCE_ENV] when an authority rejects
 * server-side downloads. Values are stamped conservatively: the RBA target is usable at the start
 * of its published effective date; an RBNZ daily observation is usable at 15:00 Pacific/Auckland
 * on the next weekday, reflecting the bank's documented one-business-day publication lag.
 */
class PolicyRateSeriesFetcher(
    private val store: MacroSeriesStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val rbaSource: String = defaultSource(RBA_SOURCE_ENV, DEFAULT_RBA_URL),
    private val rbnzSource: String = defaultSource(RBNZ_SOURCE_ENV, DEFAULT_RBNZ_URL),
) {
    private val artifacts = PolicyRateArtifactReader(http)

    /** Fetch [series] for `[from, to]`, including both source legs when deriving the differential. */
    fun fetch(
        series: PolicyRateSeries,
        from: LocalDate,
        to: LocalDate,
    ) {
        require(!to.isBefore(from)) { "policy-rate range ends before it starts: $from..$to" }
        when (series) {
            PolicyRateSeries.RBA_CASH_RATE -> fetchRba(from, to)
            PolicyRateSeries.RBNZ_OCR -> fetchRbnz(from, to)
            PolicyRateSeries.RBA_RBNZ_DIFFERENTIAL -> {
                fetchRba(from.minusDays(DERIVATION_LOOKBACK_DAYS), to)
                fetchRbnz(from.minusDays(DERIVATION_LOOKBACK_DAYS), to)
                writeDifferential(from, to)
            }
        }
    }

    private fun fetchRba(
        from: LocalDate,
        to: LocalDate,
    ) {
        val artifact = readArtifact(rbaSource, "RBA")
        val points =
            parseOfficialWorkbook(artifact, "Cash Rate Target") { date ->
                date.atStartOfDay(RBA_ZONE).toInstant().toEpochMilli()
            }
        store.write(PolicyRateSeries.RBA_CASH_RATE.id, points.filterDateRange(from, to))
        store.writeProvenance(
            PolicyRateSeries.RBA_CASH_RATE.id,
            MacroProvenance(sources = mapOf(rbaSource to artifact.sha256())),
        )
    }

    private fun fetchRbnz(
        from: LocalDate,
        to: LocalDate,
    ) {
        val artifact = readArtifact(rbnzSource, "RBNZ")
        val points =
            parseOfficialWorkbook(artifact, "Official Cash Rate") { date ->
                NewZealandBusinessCalendar
                    .nextBusinessDay(date)
                    .atTime(LocalTime.of(15, 0))
                    .atZone(RBNZ_ZONE)
                    .toInstant()
                    .toEpochMilli()
            }
        store.write(PolicyRateSeries.RBNZ_OCR.id, points.filterDateRange(from, to))
        store.writeProvenance(
            PolicyRateSeries.RBNZ_OCR.id,
            MacroProvenance(sources = mapOf(rbnzSource to artifact.sha256())),
        )
    }

    private fun writeDifferential(
        from: LocalDate,
        to: LocalDate,
    ) {
        val lookback = from.minusDays(DERIVATION_LOOKBACK_DAYS)
        val rba = store.read(PolicyRateSeries.RBA_CASH_RATE.id, lookback, to).associateBy { it.date }
        val rbnz = store.read(PolicyRateSeries.RBNZ_OCR.id, lookback, to).associateBy { it.date }
        var lastRba: MacroPoint? = null
        var lastRbnz: MacroPoint? = null
        val derived =
            (rba.keys + rbnz.keys).sorted().mapNotNull { date ->
                rba[date]?.let { lastRba = it }
                rbnz[date]?.let { lastRbnz = it }
                val left = lastRba ?: return@mapNotNull null
                val right = lastRbnz ?: return@mapNotNull null
                if (date !in from..to) return@mapNotNull null
                MacroPoint(
                    date = date,
                    value = left.value.subtract(right.value),
                    availableAtMs = maxOf(left.availableAtMs ?: 0L, right.availableAtMs ?: 0L),
                )
            }
        store.write(PolicyRateSeries.RBA_RBNZ_DIFFERENTIAL.id, derived)
        val sources =
            listOfNotNull(
                store.readProvenance(PolicyRateSeries.RBA_CASH_RATE.id),
                store.readProvenance(PolicyRateSeries.RBNZ_OCR.id),
            ).flatMap { it.sources.entries }
                .associate { it.key to it.value }
        store.writeProvenance(
            PolicyRateSeries.RBA_RBNZ_DIFFERENTIAL.id,
            MacroProvenance(sources = sources.toSortedMap()),
        )
    }

    private fun readArtifact(
        source: String,
        authority: String,
    ): ByteArray = artifacts.read(source, authority)

    private fun List<MacroPoint>.filterDateRange(
        from: LocalDate,
        to: LocalDate,
    ): List<MacroPoint> = filter { it.date in from..to }

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val RBA_SOURCE_ENV = "QKT_RBA_POLICY_RATE_SOURCE"
        const val RBNZ_SOURCE_ENV = "QKT_RBNZ_POLICY_RATE_SOURCE"
        const val DEFAULT_RBA_URL = "https://www.rba.gov.au/statistics/tables/xls/f01d.xlsx"
        const val DEFAULT_RBNZ_URL =
            "https://rbnz.govt.nz/-/media/project/sites/rbnz/files/statistics/series/b/b2/hb2-daily-close.xlsx"
        private const val DERIVATION_LOOKBACK_DAYS = 45L
        private val RBA_ZONE = ZoneId.of("Australia/Sydney")
        private val RBNZ_ZONE = ZoneId.of("Pacific/Auckland")

        private fun defaultSource(
            environmentVariable: String,
            fallback: String,
        ): String = System.getenv(environmentVariable)?.takeIf { it.isNotBlank() } ?: fallback
    }
}
