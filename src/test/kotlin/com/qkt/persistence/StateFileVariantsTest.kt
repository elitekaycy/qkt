package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateFileVariantsTest {
    private val market =
        OrderRequest.Market(
            id = "m-1",
            symbol = "XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "alpha",
        )

    private val timeExit =
        OrderRequest.TimeExit(
            id = "t-1",
            symbol = "XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.1"),
            target = market,
            deadline = Instant.ofEpochMilli(10_000L),
            onExpiry = ExpiryAction.CANCEL,
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "alpha",
        )

    private fun oco(request: OrderRequest) = PersistedOcoLeg(request.id, "b-${request.id}", "alpha", request, listOf("x"))

    private fun trail(request: OrderRequest) =
        PersistedTrailingStop(request.id, null, "alpha", request, armed = true, hwm = BigDecimal("4710"))

    private fun Path.bumpVersion() = writeText(readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

    @Test
    fun `oco legs with a composite request are skipped and the rest restore`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveOcoLegs("alpha", listOf(oco(timeExit), oco(market)))

        assertThat(FileStatePersistor(tmp).loadOcoLegs("alpha")).containsExactly(oco(market))
    }

    @Test
    fun `trailing stops with a composite request are skipped and the rest restore`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveTrailingStops("alpha", listOf(trail(timeExit), trail(market)))

        assertThat(FileStatePersistor(tmp).loadTrailingStops("alpha")).containsExactly(trail(market))
    }

    @Test
    fun `unreadable oco and trailing stop files fail the load loudly`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("alpha").toFile().mkdirs()
        tmp.resolve("alpha/oco-legs.json").writeText("{oops")
        tmp.resolve("alpha/trailing-stops.json").writeText("{oops")

        assertThatThrownBy { FileStatePersistor(tmp).loadOcoLegs("alpha") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("loadOcoLegs parse failed for alpha")
        assertThatThrownBy { FileStatePersistor(tmp).loadTrailingStops("alpha") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("loadTrailingStops parse failed for alpha")
    }

    @Test
    fun `oco and trailing stop files from another schema version are rejected`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveOcoLegs("alpha", listOf(oco(market)))
        FileStatePersistor(tmp).saveTrailingStops("alpha", listOf(trail(market)))
        tmp.resolve("alpha/oco-legs.json").bumpVersion()
        tmp.resolve("alpha/trailing-stops.json").bumpVersion()

        assertThatThrownBy { FileStatePersistor(tmp).loadOcoLegs("alpha") }
            .hasMessageContaining("loadOcoLegs schema mismatch")
        assertThatThrownBy { FileStatePersistor(tmp).loadTrailingStops("alpha") }
            .hasMessageContaining("loadTrailingStops schema mismatch")
    }

    @Test
    fun `risk state keeps every strategy halt across a restart`(
        @TempDir tmp: Path,
    ) {
        val state =
            PersistedRiskState(
                epochDay = 20_000L,
                realizedToday = BigDecimal("-10"),
                perStrategyRealizedToday = emptyMap(),
                halted = false,
                haltReason = null,
                haltScope = "NONE",
                haltEpochDay = 0L,
                strategyHalts =
                    listOf(
                        PersistedStrategyHalt("alpha", "max loss", "DAY", 20_000L, haltedAtMs = 77L),
                        PersistedStrategyHalt("beta", "manual", "SESSION", 20_000L),
                    ),
            )
        FileStatePersistor(tmp).saveRiskState("daemon", state)

        assertThat(FileStatePersistor(tmp).loadRiskState("daemon")).isEqualTo(state)
    }
}
