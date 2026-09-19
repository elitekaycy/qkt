package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.positions.IntentBook
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RestartExcursionTest {
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
