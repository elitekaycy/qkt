package com.qkt.instrument

/**
 * A venue's report of one instrument: the [meta] it states, and the [unreported] cost fields it
 * could not state, each with the reason. An unreported field keeps its [InstrumentMeta] default in
 * [meta], which is not the venue's value: `qkt instruments pull` writes it as a note to set by hand
 * rather than as a silent zero.
 *
 * e.g. an MT5 symbol whose swap is quoted in money rather than points reports
 * `unreported = mapOf(UnreportedCost.SWAP to "swap_mode 2 is not points")`.
 */
data class VenueInstrumentSpec(
    val meta: InstrumentMeta,
    val unreported: Map<UnreportedCost, String> = emptyMap(),
)

/** The cost fields a venue's symbol endpoint may be unable to report, by the YAML keys each covers. */
enum class UnreportedCost(
    val keys: List<String>,
) {
    COMMISSION(listOf("commissionPerLot")),
    SWAP(listOf("swapLongPoints", "swapShortPoints", "swapTripleDay")),
}
