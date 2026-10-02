package com.qkt.derivatives.futures

import com.qkt.common.Side
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Properties of the forward adjustment over seeded random chains: a contract price that is on the
 * tick grid survives the round trip to the continuous series and back unchanged, on both sides and
 * for both order kinds, and appending rolls never changes an earlier contract's shift.
 */
class PriceSpacePropertyTest {
    private val tick = BigDecimal("0.1")

    private fun onGrid(random: Random): BigDecimal = BigDecimal.valueOf(200_000L + random.nextLong(900_000L), 1)

    /** A roll whose next contract trades within ±2% of the expiring one, both on the tick grid. */
    private fun roll(random: Random): RollPrices {
        val from = onGrid(random)
        val gapTicks = (from.movePointRight(1).toLong() * (random.nextDouble() * 0.04 - 0.02)).toLong()
        return RollPrices(from, from.add(BigDecimal.valueOf(gapTicks, 1)))
    }

    private fun chain(
        random: Random,
        adjustment: PriceAdjustment,
        rolls: Int,
    ): AdjustmentChain = AdjustmentChain(adjustment, List(rolls) { roll(random) })

    @Test
    fun `on-grid contract prices round-trip exactly under every adjustment`() {
        val random = Random(42)
        for (adjustment in PriceAdjustment.entries) {
            repeat(400) {
                val chain = chain(random, adjustment, rolls = 6)
                val index = random.nextInt(chain.size)
                val space = PriceSpace(adjustment, chain.shiftFor(index), tick)
                val raw = onGrid(random)
                val continuous = space.toContinuous(raw)
                for (side in Side.entries) {
                    assertThat(
                        space.limitToContract(continuous, side),
                    ).describedAs("$adjustment limit $side").isEqualByComparingTo(raw)
                    assertThat(
                        space.stopToContract(continuous, side),
                    ).describedAs("$adjustment stop $side").isEqualByComparingTo(raw)
                }
            }
        }
    }

    @Test
    fun `appending rolls never changes an earlier shift`() {
        val random = Random(7)
        for (adjustment in PriceAdjustment.entries) {
            val rolls = List(8) { roll(random) }
            val full = AdjustmentChain(adjustment, rolls)
            for (k in 0..rolls.size) {
                val prefix = AdjustmentChain(adjustment, rolls.take(k))
                for (i in 0 until prefix.size) assertThat(prefix.shiftFor(i)).isEqualByComparingTo(full.shiftFor(i))
            }
        }
    }
}
