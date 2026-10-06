package com.qkt.derivatives.futures

import com.qkt.instrument.ListedContract
import java.math.BigDecimal

/**
 * Which listed contracts a continuous chain may roll into (qkt#1341). Some roots list every calendar
 * month but trade only some of them (COMEX gold: G/J/M/Q/V/Z); a chain stepping into a listed but
 * untraded month never sees a real price and breaks. A contract is judged against its chronological
 * neighbours ([NEIGHBOURS] on each side), never against a fixed number or an all-time figure, so the
 * rule holds across decades of market growth: it is dropped when its [ListedContract.lifetimeVolume] is
 * below [VOLUME_SHARE] of the neighbours' median, unless its [ListedContract.peakOpenInterest] reaches
 * [OPEN_INTEREST_SHARE] of theirs (an archive can under-report volume while open interest is intact).
 * A contract without a volume figure is always kept, so catalogs that give none chain unchanged.
 */
internal object ActiveDeliveryMonths {
    private const val NEIGHBOURS = 6
    private val VOLUME_SHARE = BigDecimal("0.10")
    private val OPEN_INTEREST_SHARE = BigDecimal("0.50")

    /** [contracts] (in expiry order) without the ones that never traded next to their neighbours. */
    fun of(contracts: List<ListedContract>): List<ListedContract> {
        val volumes = contracts.map { it.lifetimeVolumeOrNull() }
        val interests = contracts.map { it.peakOpenInterestOrNull() }
        return contracts.filterIndexed { i, _ ->
            val volume = volumes[i] ?: return@filterIndexed true
            reaches(volume, median(volumes, i), VOLUME_SHARE) ||
                interests[i]?.let { reaches(it, median(interests, i), OPEN_INTEREST_SHARE) } == true
        }
    }

    /** True when [value] is at least [share] of [median], or there is no positive median to compare with. */
    private fun reaches(
        value: BigDecimal,
        median: BigDecimal?,
        share: BigDecimal,
    ): Boolean = median == null || median.signum() <= 0 || value >= median.multiply(share)

    /** The median of the known figures of [i]'s neighbours, or null when none is known. */
    private fun median(
        figures: List<BigDecimal?>,
        i: Int,
    ): BigDecimal? {
        val around =
            (maxOf(0, i - NEIGHBOURS)..minOf(figures.lastIndex, i + NEIGHBOURS))
                .filter { it != i }
                .mapNotNull { figures[it] }
                .sorted()
        if (around.isEmpty()) return null
        val mid = around.size / 2
        return if (around.size % 2 == 1) around[mid] else around[mid - 1].add(around[mid]).divide(TWO)
    }

    private val TWO = BigDecimal(2)
}
