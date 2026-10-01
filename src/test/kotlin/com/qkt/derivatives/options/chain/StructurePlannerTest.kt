package com.qkt.derivatives.options.chain

import com.qkt.common.Side
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionRight
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.nio.file.Paths
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The real book snapshot of `btc-usdc-book-20261001`; picks and loss recomputed by an independent Python script. */
class StructurePlannerTest {
    private val dir = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-book-20261001")).toURI())
    private val snapshot =
        ChainSnapshotStore(
            dir,
            QuoteSource.BOOK,
        ).readDay("DERIBIT:BTC_USDC", LocalDate.parse("2026-10-01")).single()
    private val listings =
        Json
            .decodeFromString(
                OptionCatalog.serializer(),
                dir.resolve("contracts/DERIBIT/BTC_USDC.options.json").toFile().readText(),
            ).contracts
            .associateBy { it.symbol }

    private fun plan(vararg specs: LegSpec) =
        StructurePlanner.plan(specs.toList(), snapshot, listings, 3_600_000L, BigDecimal.ONE, snapshot.atMs)

    @Test
    fun `a put spread selects both legs in one expiry and knows its loss per unit`() {
        val ready =
            plan(
                LegSpec(Side.SELL, OptionRight.PUT, 0.25, 7.0, 30.0),
                LegSpec(Side.BUY, OptionRight.PUT, 0.10, null, null),
            ) as StructurePlan.Ready

        assertThat(ready.legs.map { it.contract }).containsExactly("BTC_USDC-9OCT26-81000-P", "BTC_USDC-9OCT26-78000-P")
        assertThat(ready.legs.map { it.side }).containsExactly(Side.SELL, Side.BUY)
        // value -646.35394757 + 219.0038852, worst payoff -3000 at or below 78000.
        assertThat(ready.maxLossPerUnit).isEqualByComparingTo("2572.64993763")
    }

    @Test
    fun `a naked short call has unbounded loss and a leg that selects nothing refuses the plan`() {
        val naked = plan(LegSpec(Side.SELL, OptionRight.CALL, 0.25, 7.0, 30.0)) as StructurePlan.Ready
        assertThat(naked.maxLossPerUnit).isNull()

        val missing =
            plan(
                LegSpec(Side.SELL, OptionRight.PUT, 0.25, 7.0, 30.0),
                LegSpec(Side.BUY, OptionRight.PUT, 0.10, 200.0, 300.0),
            )
        assertThat((missing as StructurePlan.Refused).reason).contains("leg 2")
    }

    @Test
    fun `a calendar's loss sums each expiry's loss, never netting the far short against the near long`() {
        val ready =
            plan(
                LegSpec(Side.BUY, OptionRight.PUT, 0.25, 7.0, 30.0),
                LegSpec(Side.SELL, OptionRight.PUT, 0.25, 50.0, 70.0),
            ) as StructurePlan.Ready
        val (near, far) = ready.legs

        assertThat(near.contract).contains("9OCT26")
        assertThat(far.contract).contains("27NOV26")
        // Near long put: its premium. Far short put: its strike (paid at 0) less its premium.
        val farStrike = BigDecimal(far.listing.strike)
        assertThat(ready.maxLossPerUnit).isEqualByComparingTo(near.mark.add(farStrike).subtract(far.mark))
    }

    @Test
    fun `two legs that select the same contract refuse the plan`() {
        val same =
            plan(
                LegSpec(Side.SELL, OptionRight.PUT, 0.25, 7.0, 30.0),
                LegSpec(Side.BUY, OptionRight.PUT, 0.25, null, null),
            )

        assertThat(
            (same as StructurePlan.Refused).reason,
        ).isEqualTo("legs 1 and 2 select the same contract BTC_USDC-9OCT26-81000-P")
    }
}
