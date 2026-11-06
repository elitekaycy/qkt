package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainEdgeCasesTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            "crypto",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val catalog =
        ContractCatalog(
            root.root,
            listOf(
                ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                ListedContract("BTCUSDT_250328", ms("2025-03-28T08:00:00Z")),
            ),
        )
    private val history =
        RollHistory(
            root.root,
            "8d@08:00",
            listOf(
                RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800"),
                RollRecord(ms("2024-12-19T08:00:00Z"), "BTCUSDT_241227", "BTCUSDT_250328", "97000", "98500"),
                RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_241227", "BTCUSDT_250328", "63800", "64700"),
            ),
        )

    @Test
    fun `a next stream ends when its last contract becomes the front one`() {
        val next = ContinuousChain(root, catalog, history, ContinuousSelector.NEXT)

        assertThat(next.endReason).isEqualTo("its last contract becomes the front one")
    }

    @Test
    fun `a contract before the measured history has no price space`() {
        val next = ContinuousChain(root, catalog, history, ContinuousSelector.NEXT)

        assertThat(next.covers(0)).isFalse()
        assertThatThrownBy { next.spaceFor(0) }
            .hasMessageContaining("needs BINANCE_UM:BTCUSDT_240927, before the roll history starts")
    }

    @Test
    fun `a continuous stream of a root without a catalog names the fetch that builds one`() {
        val chains = ContinuousChains(ContractCatalogRegistry(listOf(root), emptyMap()))

        assertThatThrownBy { chains.chainFor("BINANCE_UM:BTCUSDT@front") }
            .hasMessage("no contract catalog for BINANCE_UM:BTCUSDT; run qkt fetch BINANCE_UM:BTCUSDT --catalog")
    }

    @Test
    fun `a live roll with no contract for the selector is not the next one`(
        @TempDir dir: Path,
    ) {
        val schedule = RollSchedule(catalog.contracts, root.roll!!)
        val measurer = LiveRollMeasurer({ _, _, _ -> emptyList() }, RollHistoryStore(dir))

        val roll = measurer.measure(root, catalog, ContinuousSelector.NEXT, schedule.transitions.last())

        assertThat((roll as LiveRoll.NotNext).reason).isEqualTo("the chain has no contract for NEXT here")
    }

    @Test
    fun `an anchor outside the chain is refused and none adjustment keeps raw prices at any anchor`() {
        val rolls = listOf(RollPrices(BigDecimal("100"), BigDecimal("110")))

        assertThatThrownBy { AdjustmentChain(PriceAdjustment.PANAMA, rolls, anchor = 5) }
            .hasMessage("anchor index 5 is outside the adjusted chain (0..1)")
        val none = AdjustmentChain(PriceAdjustment.NONE, rolls, anchor = 1)
        assertThat(none.toContinuous(0, BigDecimal("100"))).isEqualByComparingTo("100")
        assertThat(none.shiftFor(1)).isEqualByComparingTo("0")
    }
}
