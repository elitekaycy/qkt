package com.qkt.derivatives.futures

import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A roll measured live extends a root's streams; the contracts already mapped keep their mapping. */
class ContinuousChainsHistoryTest {
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
            null,
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
    private val first = RollRecord(ms("2024-09-19T08:00:00Z"), "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800")
    private val second = RollRecord(ms("2024-12-19T08:00:00Z"), "BTCUSDT_241227", "BTCUSDT_250328", "97000", "98500")
    private val front = "BINANCE_UM:BTCUSDT@front"

    private fun chains() =
        ContinuousChains(
            requireNotNull(
                ContractCatalogRegistry(
                    listOf(root),
                    mapOf(root.root to catalog),
                    mapOf(root.root to RollHistory(root.root, "8d@08:00", listOf(first))),
                ).futures(),
            ),
        )

    @Test
    fun `a longer history rebuilds the root's streams, and contracts already mapped keep their mapping`() {
        val chains = chains()
        val before = chains.chainFor(front)!!
        assertThat(before.covers(2)).isFalse()

        chains.useHistory(root.root, RollHistory(root.root, "8d@08:00", listOf(first, second)))

        val after = chains.chainFor(front)!!
        assertThat(after.covers(2)).isTrue()
        assertThat(after.rollOutOf(1).atMs).isEqualTo(second.atMs)
        for (index in 0..1) {
            assertThat(after.spaceFor(index).toContinuous(BigDecimal("90000")))
                .isEqualByComparingTo(before.spaceFor(index).toContinuous(BigDecimal("90000")))
        }
    }

    @Test
    fun `a history for another root changes nothing`() {
        val chains = chains()

        chains.useHistory("BINANCE_UM:ETHUSDT", RollHistory("BINANCE_UM:ETHUSDT", "8d@08:00", emptyList()))

        assertThat(chains.chainFor(front)!!.covers(2)).isFalse()
    }
}
