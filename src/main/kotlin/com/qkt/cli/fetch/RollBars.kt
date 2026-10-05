package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Path
import java.time.LocalDate

/**
 * The stored [timeframe] bars of [venue]'s contracts a roll history is measured from: a day fetched
 * into the CSV store ([LocalBarStore]) or, failing that, one built or imported into the binary store
 * ([BinaryBarStore]), as a vendor archive's per-contract bars are.
 */
internal class RollBars(
    dataRoot: Path,
    private val venue: String,
    private val timeframe: TimeWindow,
) {
    private val fetched = LocalBarStore(dataRoot)
    private val built = BinaryBarStore(dataRoot)
    private val label = timeframe.canonicalSpec()

    /** Whether either store holds [contract]'s [day]. */
    fun hasDay(
        contract: String,
        day: LocalDate,
    ): Boolean = fetched.hasDay(venue, contract, label, day) || built.hasDay(venue, contract, timeframe, day)

    /** [contract]'s bars of [day], from whichever store holds the day; empty when neither does. */
    fun readDay(
        contract: String,
        day: LocalDate,
    ): List<Candle> =
        when {
            fetched.hasDay(venue, contract, label, day) -> fetched.readDay(venue, contract, label, day)
            built.hasDay(venue, contract, timeframe, day) -> built.readDay(venue, contract, timeframe, day)
            else -> emptyList()
        }
}
