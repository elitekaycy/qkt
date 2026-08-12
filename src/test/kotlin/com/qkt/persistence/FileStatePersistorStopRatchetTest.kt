package com.qkt.persistence

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorStopRatchetTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ratchet request and progress round trip through the restart journal`() {
        val stepped =
            OrderRequest.SteppedStop(
                id = "step-sl",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                entryPrice = Money.of("100"),
                initialDistance = Money.of("50"),
                steps =
                    listOf(
                        StopLossSpec.Step(Money.of("30"), Money.ZERO),
                        StopLossSpec.Step(Money.of("70"), Money.of("40")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 10L,
                strategyId = "alpha",
            )
        val time =
            OrderRequest.TimeTighteningStop(
                id = "time-sl",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                entryPrice = Money.of("100"),
                initialDistance = Money.of("60"),
                tightenBy = Money.of("10"),
                intervalMs = 900_000L,
                floorDistance = Money.of("20"),
                timeInForce = TimeInForce.GTC,
                timestamp = 20L,
                strategyId = "alpha",
            )
        FileStatePersistor(tempDir).saveTrailingStops(
            "alpha",
            listOf(
                PersistedTrailingStop(
                    clientOrderId = stepped.id,
                    brokerOrderId = null,
                    strategyId = "alpha",
                    request = stepped,
                    armed = false,
                    hwm = Money.of("130"),
                    stepIndex = 1,
                    stopLevel = Money.of("100"),
                ),
                PersistedTrailingStop(
                    clientOrderId = time.id,
                    brokerOrderId = null,
                    strategyId = "alpha",
                    request = time,
                    armed = false,
                    hwm = Money.of("100"),
                    elapsedIntervals = 3L,
                    stopLevel = Money.of("70"),
                ),
            ),
        )

        val loaded = FileStatePersistor(tempDir).loadTrailingStops("alpha")

        assertThat(loaded).hasSize(2)
        assertThat(loaded[0].request).isEqualTo(stepped)
        assertThat(loaded[0].stepIndex).isEqualTo(1)
        assertThat(loaded[0].stopLevel).isEqualByComparingTo("100")
        assertThat(loaded[1].request).isEqualTo(time)
        assertThat(loaded[1].elapsedIntervals).isEqualTo(3L)
        assertThat(loaded[1].stopLevel).isEqualByComparingTo("70")
    }

    @Test
    fun `basic trailing high-water state round trips through the restart journal`() {
        val trailing =
            OrderRequest.TrailingStop(
                id = "trail",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("5"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 10L,
                strategyId = "alpha",
            )
        FileStatePersistor(tempDir).saveTrailingStops(
            "alpha",
            listOf(
                PersistedTrailingStop(
                    clientOrderId = trailing.id,
                    brokerOrderId = trailing.id,
                    strategyId = "alpha",
                    request = trailing,
                    armed = false,
                    hwm = Money.of("123.45"),
                ),
            ),
        )

        val loaded = FileStatePersistor(tempDir).loadTrailingStops("alpha").single()

        assertThat(loaded.request).isEqualTo(trailing)
        assertThat(loaded.hwm).isEqualByComparingTo("123.45")
    }
}
