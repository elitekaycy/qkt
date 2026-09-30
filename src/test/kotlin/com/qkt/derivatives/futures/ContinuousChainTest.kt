package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContinuousChainTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val policy = RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA)
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
            policy,
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
    private val front = ContinuousChain(root, catalog, history, ContinuousSelector.FRONT)

    @Test
    fun `front follows the schedule and shifts forward from the anchor`() {
        assertThat(front.symbol).isEqualTo("BINANCE_UM:BTCUSDT@front")
        assertThat(front.contractSymbolAt(ms("2024-09-01T00:00:00Z"))).isEqualTo("BINANCE_UM:BTCUSDT_240927")
        assertThat(front.contractSymbolAt(ms("2024-10-01T00:00:00Z"))).isEqualTo("BINANCE_UM:BTCUSDT_241227")
        assertThat(front.spaceFor(0).toContinuous(BigDecimal("63000"))).isEqualByComparingTo("63000")
        assertThat(front.spaceFor(1).toContinuous(BigDecimal("63800"))).isEqualByComparingTo("63000")
        assertThat(front.spaceFor(2).toContinuous(BigDecimal("98500"))).isEqualByComparingTo("96200")
    }

    @Test
    fun `next uses its own roll pair`() {
        val next = ContinuousChain(root, catalog, history, ContinuousSelector.NEXT)
        assertThat(next.contractSymbolAt(ms("2024-09-01T00:00:00Z"))).isEqualTo("BINANCE_UM:BTCUSDT_241227")
        assertThat(next.anchorIndex).isEqualTo(1)
        assertThat(next.spaceFor(2).toContinuous(BigDecimal("64700"))).isEqualByComparingTo("63800")
        assertThat(next.contractSymbolAt(ms("2025-01-01T00:00:00Z"))).isNull()
    }

    @Test
    fun `segments start at the first measured roll and split exactly at later rolls`() {
        assertThat(front.servedFromMs).isEqualTo(ms("2024-09-19T08:00:00Z"))
        assertThat(front.segments(ms("2024-09-18T00:00:00Z"), ms("2024-09-20T00:00:00Z")))
            .containsExactly(ChainSegment(1, ms("2024-09-19T08:00:00Z"), ms("2024-09-20T00:00:00Z")))
        assertThat(front.segments(ms("2024-12-18T00:00:00Z"), ms("2024-12-20T00:00:00Z"))).containsExactly(
            ChainSegment(1, ms("2024-12-18T00:00:00Z"), ms("2024-12-19T08:00:00Z")),
            ChainSegment(2, ms("2024-12-19T08:00:00Z"), ms("2024-12-20T00:00:00Z")),
        )
        assertThat(front.segments(ms("2024-09-01T00:00:00Z"), ms("2024-09-02T00:00:00Z"))).isEmpty()
    }

    @Test
    fun `a window past the last measured roll names the command that builds it`() {
        val short = history.copy(rolls = history.rolls.take(1))
        val chain = ContinuousChain(root, catalog, short, ContinuousSelector.FRONT)
        assertThatThrownBy { chain.spaceFor(2) }.hasMessageContaining("qkt fetch BINANCE_UM:BTCUSDT --rolls")
    }

    @Test
    fun `a history built under another policy is refused`() {
        assertThatThrownBy {
            ContinuousChain(
                root,
                catalog,
                history.copy(policy = "5d@08:00"),
                ContinuousSelector.FRONT,
            )
        }.hasMessageContaining("5d@08:00")
            .hasMessageContaining("8d@08:00")
    }

    @Test
    fun `a root without a roll policy cannot back a continuous stream`() {
        assertThatThrownBy { ContinuousChain(root.copy(roll = null), catalog, history, ContinuousSelector.FRONT) }
            .hasMessageContaining("roll")
    }

    @Test
    fun `a history that skips a roll is refused`() {
        val later = ListedContract("BTCUSDT_250627", ms("2025-06-27T08:00:00Z"))
        val gappy =
            history.copy(
                rolls =
                    listOf(
                        history.rolls[0],
                        RollRecord(ms("2025-03-20T08:00:00Z"), "BTCUSDT_250328", "BTCUSDT_250627", "80000", "81000"),
                    ),
            )
        assertThatThrownBy {
            ContinuousChain(root, catalog.copy(contracts = catalog.contracts + later), gappy, ContinuousSelector.FRONT)
        }.hasMessageContaining("skips")
    }
}
