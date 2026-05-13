package com.qkt.tools.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.persistence.BracketPair
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.PersistedTier
import com.qkt.persistence.PersistedTierState
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * Throwaway demo that exercises [FileStatePersistor] end-to-end against a temp dir and
 * prints the resulting file layout. Equivalent to what the daemon would write while a
 * hedge-straddle is mid-trade with one STACK_AT tier already fired.
 *
 * Run via: `./gradlew runStateDemo`
 */
fun main() {
    val dir = Path.of(System.getenv("DEMO_DIR") ?: "/tmp/qkt-demo-state")
    Files.createDirectories(dir)
    println("[state-demo] root: $dir")

    val persistor = FileStatePersistor(dir)
    val strategy = "hedge-straddle"

    // 1. LegBook with PRIMARY + STACK
    val book = LegBook("XAUUSDm")
    book.add(
        PositionLeg(
            legId = "hedge-straddle-XAUUSDm-primary-1",
            symbol = "XAUUSDm",
            side = Side.BUY,
            quantity = BigDecimal("0.20"),
            entryPrice = BigDecimal("4700.000"),
            openedAt = 1715607600000L,
            role = LegRole.PRIMARY,
        ),
    )
    book.add(
        PositionLeg(
            legId = "leg-primary-stack-tier0",
            parentLegId = "hedge-straddle-XAUUSDm-primary-1",
            symbol = "XAUUSDm",
            side = Side.BUY,
            quantity = BigDecimal("0.06"),
            entryPrice = BigDecimal("4710.500"),
            openedAt = 1715608500000L,
            role = LegRole.STACK,
        ),
    )
    persistor.saveLegBook(strategy, "XAUUSDm", book)

    // 2. Bracket pairs
    persistor.saveBracketPairs(
        strategy,
        listOf(
            BracketPair("c-1", "c-1-sl", "c-1-tp", legId = null),
            BracketPair(
                "leg-primary-stack-tier0-entry",
                "leg-primary-stack-tier0-sl",
                "leg-primary-stack-tier0-tp",
                legId = null,
            ),
        ),
    )

    // 3. Pending orders (a working STOP that hasn't been acked yet)
    persistor.savePendingOrders(
        strategy,
        mapOf(
            "c-2" to
                OrderRequest.Stop(
                    id = "c-2",
                    symbol = "XAUUSDm",
                    side = Side.SELL,
                    quantity = BigDecimal("0.20"),
                    stopPrice = BigDecimal("4690.0"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1715607600000L,
                    strategyId = strategy,
                ),
        ),
    )

    // 4. Pending stacks — tier 0 already fired, tier 1 still armed
    persistor.savePendingStacks(
        strategy,
        mapOf(
            "hedge-straddle-XAUUSDm-primary-1" to
                PersistedTierState(
                    primaryClientOrderId = "c-1",
                    tiers =
                        listOf(
                            PersistedTier(
                                index = 0,
                                mfeThreshold = BigDecimal("10"),
                                withinMs = 1_800_000L,
                                stackQuantity = BigDecimal("0.06"),
                                slDistance = BigDecimal("200"),
                                tpDistance = BigDecimal("2000"),
                                fired = true,
                                firedAt = 1715608500000L,
                                firedLegId = "leg-primary-stack-tier0",
                            ),
                            PersistedTier(
                                index = 1,
                                mfeThreshold = BigDecimal("20"),
                                withinMs = 3_600_000L,
                                stackQuantity = BigDecimal("0.06"),
                                slDistance = BigDecimal("200"),
                                tpDistance = BigDecimal("2000"),
                                fired = false,
                                firedAt = null,
                                firedLegId = null,
                            ),
                        ),
                ),
        ),
    )

    // Dump the resulting tree.
    println("[state-demo] files written:")
    Files.walk(dir).filter { Files.isRegularFile(it) }.sorted().forEach { p ->
        println("  ${p.relativize(dir).let { dir.relativize(p) }} (${Files.size(p)} bytes)")
    }
    println()
    println("[state-demo] inspect with:")
    println("  cat $dir/$strategy/XAUUSDm-legbook.json | jq .")
    println("  cat $dir/$strategy/pending-stacks.json | jq .")
}
