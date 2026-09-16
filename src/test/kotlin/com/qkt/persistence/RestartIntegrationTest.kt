package com.qkt.persistence

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.compile.CompiledStackTier
import com.qkt.dsl.compile.StackOrchestrator
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.IntentBook
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end Phase 29 contract: tier-fired state persists across a simulated restart,
 * and the re-created [StackOrchestrator] seeds the new [com.qkt.dsl.compile.StackEngine]
 * with `initialFiredTierIndices` from disk so MFE re-crossing the threshold post-restart
 * does NOT re-fire the tier.
 */
class RestartIntegrationTest {
    private val parentLegId = "hedge-straddle-XAUUSDm-primary-1"
    private val tier =
        CompiledStackTier(
            mfeThreshold = BigDecimal("10"),
            withinMs = 1_800_000L,
            resolveStackQuantity = { _ -> BigDecimal("0.06") },
            slDistance = BigDecimal("200"),
            tpDistance = BigDecimal("2000"),
        )

    private fun primaryFill(strategyId: String) =
        BrokerEvent.OrderFilled(
            clientOrderId = "c-1",
            brokerOrderId = null,
            symbol = "XAUUSDm",
            side = Side.BUY,
            price = BigDecimal("4700"),
            quantity = BigDecimal("0.20"),
            strategyId = strategyId,
            timestamp = 1000L,
        )

    @Test
    fun `tier-fired state survives restart and prevents double-fire`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val strategyId = "hedge-straddle"

        // ============================================================
        // Session 1: open primary, drive tick that fires tier 0, persist.
        // ============================================================
        val tracker1 = StrategyPositionTracker(persistor)
        val intents = IntentBook()
        intents.apply(tracker1, primaryFill(strategyId))
        // sanity: the primary leg is persisted
        val persistedAfterPrimary = persistor.loadLegBook(strategyId, "XAUUSDm")
        assertThat(persistedAfterPrimary).isNotNull
        assertThat(persistedAfterPrimary!!.legs.single().role).isEqualTo(LegRole.PRIMARY)

        val emitted1 = mutableListOf<Signal>()
        val orch1 =
            StackOrchestrator(
                clock = FixedClock(2000L),
                strategyId = strategyId,
                persistor = persistor,
                emit = { emitted1.add(it) },
            )
        orch1.onPrimaryFilled(
            parentLegId = parentLegId,
            parentSymbol = "XAUUSDm",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4700"),
            parentQty = BigDecimal("0.10"),
            tiers = listOf(tier),
        )
        // Price moves up enough that MFE crosses tier 0's threshold (10).
        orch1.onTick("XAUUSDm", BigDecimal("4711"))
        assertThat(emitted1).hasSize(1) // tier 0 fired
        val firedTiers = persistor.loadPendingStacks(strategyId)[parentLegId]
        assertThat(firedTiers).isNotNull
        assertThat(firedTiers!!.tiers.single().fired).isTrue

        // ============================================================
        // *** Simulated restart *** — fresh tracker, fresh orchestrator,
        // same persistor pointed at the same temp dir.
        // ============================================================
        val tracker2 = StrategyPositionTracker(persistor)
        tracker2.preloadFromPersistor(strategyId, "XAUUSDm")
        // The LegBook must rehydrate with the PRIMARY leg.
        val rebuiltBook = tracker2.legBookFor(strategyId, "XAUUSDm")
        assertThat(rebuiltBook).isNotNull
        assertThat(rebuiltBook!!.primary()?.legId).isNotNull

        val emitted2 = mutableListOf<Signal>()
        val orch2 =
            StackOrchestrator(
                clock = FixedClock(3000L),
                strategyId = strategyId,
                persistor = persistor,
                emit = { emitted2.add(it) },
            )
        // Re-attach to the same primary. The orchestrator should seed firedTierIndices
        // from the persisted state.
        orch2.onPrimaryFilled(
            parentLegId = parentLegId,
            parentSymbol = "XAUUSDm",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4700"),
            parentQty = BigDecimal("0.10"),
            tiers = listOf(tier),
        )
        // Drive another tick that crosses the tier-0 threshold. Pre-restart this fired;
        // post-restart it must NOT re-fire.
        orch2.onTick("XAUUSDm", BigDecimal("4712"))
        assertThat(emitted2)
            .withFailMessage(
                "Tier 0 re-fired after restart — R5 is broken. Persisted fired-state was not seeded.",
            ).isEmpty()
    }

    @Test
    fun `pending-orders survive restart`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val strategyId = "hedge-straddle"

        // Simulate session 1: persist a pending Stop order
        persistor.savePendingOrders(
            strategyId,
            mapOf(
                "c-stop" to
                    OrderRequest.Stop(
                        id = "c-stop",
                        symbol = "XAUUSDm",
                        side = Side.SELL,
                        quantity = BigDecimal("0.20"),
                        stopPrice = BigDecimal("4690"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1000L,
                        strategyId = strategyId,
                    ),
            ),
        )

        // Session 2: fresh persistor instance pointed at the same dir
        val persistor2 = FileStatePersistor(tmp)
        val loaded = persistor2.loadPendingOrders(strategyId)
        assertThat(loaded).hasSize(1)
        val stop = loaded["c-stop"] as OrderRequest.Stop
        assertThat(stop.stopPrice.toPlainString()).isEqualTo("4690")
        assertThat(stop.symbol).isEqualTo("XAUUSDm")
    }

    @Test
    fun `legbook with primary plus stack survives restart`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val strategyId = "hedge-straddle"

        // Session 1: open primary, then a stack leg
        val tracker1 = StrategyPositionTracker(persistor)
        val intents = IntentBook()
        intents.apply(tracker1, primaryFill(strategyId))
        tracker1.addStackLeg(
            strategyId,
            com.qkt.positions.PositionLeg(
                legId = "leg-stack-1",
                parentLegId = parentLegId,
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.06"),
                entryPrice = BigDecimal("4710"),
                openedAt = 2000L,
                role = LegRole.STACK,
            ),
        )

        // Session 2: reload via preload
        val tracker2 = StrategyPositionTracker(persistor)
        tracker2.preloadFromPersistor(strategyId, "XAUUSDm")
        val book = tracker2.legBookFor(strategyId, "XAUUSDm")
        assertThat(book).isNotNull
        assertThat(book!!.all()).hasSize(2)
        assertThat(book.primary()).isNotNull
        assertThat(book.stacks()).hasSize(1)
        assertThat(book.stacks().single().parentLegId).isEqualTo(book.primary()!!.legId)
    }

    private fun legFill(
        strategyId: String,
        clientOrderId: String,
        price: String,
        side: Side = Side.BUY,
        timestamp: Long = 1000L,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = clientOrderId,
        brokerOrderId = "t-$clientOrderId",
        symbol = "XAUUSDm",
        side = side,
        price = BigDecimal(price),
        quantity = BigDecimal("0.10"),
        strategyId = strategyId,
        timestamp = timestamp,
    )

    private fun candle(
        startTime: Long,
        high: String,
        low: String,
    ) = com.qkt.marketdata.Candle(
        symbol = "XAUUSDm",
        open = BigDecimal(low),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(high),
        volume = BigDecimal.ONE,
        startTime = startTime,
        endTime = startTime + 60_000L,
    )

    @Test
    fun `a position restored after a restart keeps tracking MFE and MAE`(
        @TempDir tmp: Path,
    ) {
        // #1158: the restored leg book had no excursion tracker, so POSITION.mfe/mae read 0 until
        // the position closed — even while it moved into profit.
        val persistor = FileStatePersistor(tmp)
        val strategyId = "trailx"
        val intents = IntentBook()
        intents.independentOpen(strategyId, "c-1", "leg-1")
        intents.apply(StrategyPositionTracker(persistor), legFill(strategyId, "c-1", "4700"))

        val restarted = StrategyPositionTracker(persistor)
        restarted.preloadFromPersistor(strategyId, "XAUUSDm")
        restarted.onTick("XAUUSDm", BigDecimal("4712"))
        restarted.onTick("XAUUSDm", BigDecimal("4696"))

        assertThat(restarted.primaryMfeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("12")
        assertThat(restarted.primaryMaeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("4")
    }

    @Test
    fun `MFE and MAE reached before a restart are restored`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val strategyId = "trailx"
        val intents = IntentBook()
        intents.independentOpen(strategyId, "c-1", "leg-1")
        val before = StrategyPositionTracker(persistor, excursionPersistIntervalMs = 0L)
        intents.apply(before, legFill(strategyId, "c-1", "4700"))
        before.onTick("XAUUSDm", BigDecimal("4730"))
        before.onTick("XAUUSDm", BigDecimal("4695"))

        val restarted = StrategyPositionTracker(persistor)
        restarted.preloadFromPersistor(strategyId, "XAUUSDm")

        assertThat(restarted.primaryMfeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("30")
        assertThat(restarted.primaryMaeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("5")
        // A later tick inside the known range moves neither mark.
        restarted.onTick("XAUUSDm", BigDecimal("4710"))
        assertThat(restarted.primaryMfeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("30")
        assertThat(restarted.primaryMaeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("5")
    }

    @Test
    fun `a persisted excursion that belongs to an earlier leg is not applied`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val strategyId = "trailx"
        val intents = IntentBook()
        intents.independentOpen(strategyId, "c-1", "leg-1")
        intents.close(strategyId, "x-1", "leg-1")
        intents.independentOpen(strategyId, "c-2", "leg-2")
        val before = StrategyPositionTracker(persistor, excursionPersistIntervalMs = 0L)
        intents.apply(before, legFill(strategyId, "c-1", "4700"))
        before.onTick("XAUUSDm", BigDecimal("4730"))
        intents.apply(before, legFill(strategyId, "x-1", "4730", side = Side.SELL, timestamp = 2000L))
        intents.apply(before, legFill(strategyId, "c-2", "4800", timestamp = 3000L))

        val restarted = StrategyPositionTracker(persistor)
        restarted.preloadFromPersistor(strategyId, "XAUUSDm")

        assertThat(restarted.primaryMfeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("0")
        assertThat(restarted.primaryMaeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("0")
    }

    @Test
    fun `candles printed while the daemon was down extend the restored excursion`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val strategyId = "trailx"
        val intents = IntentBook()
        intents.independentOpen(strategyId, "c-1", "leg-1")
        val before = StrategyPositionTracker(persistor, excursionPersistIntervalMs = 0L)
        intents.apply(before, legFill(strategyId, "c-1", "4700", timestamp = 1_000_000L))
        before.onTick("XAUUSDm", BigDecimal("4730"))
        before.onTick("XAUUSDm", BigDecimal("4695"))

        val restarted = StrategyPositionTracker(persistor)
        restarted.preloadFromPersistor(strategyId, "XAUUSDm")
        restarted.extendExcursion(
            strategyId,
            "XAUUSDm",
            listOf(
                // Opened before the entry: its range includes pre-entry prices and must not count.
                candle(startTime = 960_000L, high = "4900", low = "4500"),
                // Printed during the downtime.
                candle(startTime = 1_200_000L, high = "4745", low = "4690"),
                candle(startTime = 1_260_000L, high = "4720", low = "4692"),
            ),
        )

        assertThat(restarted.primaryMfeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("45")
        assertThat(restarted.primaryMaeFor(strategyId, "XAUUSDm")).isEqualByComparingTo("10")
    }
}

// Unused but kept for the integration-test surface — confirms we have access to
// OrderEvent without a missing import warning in some toolchains.
@Suppress("unused")
private val unused: OrderEvent? = null
