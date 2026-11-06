package com.qkt.backtest

import com.qkt.broker.FixedPointsSlippage
import com.qkt.broker.FractionalPartialFill
import com.qkt.broker.FullFill
import com.qkt.broker.InstrumentSlippage
import com.qkt.broker.NoBrokerRejections
import com.qkt.broker.RejectEveryNthOrder
import com.qkt.broker.UniformRandomSlippage
import com.qkt.broker.ZeroSlippage
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionSimulationConfigTest {
    @Test
    fun `every preset alias resolves and an unknown one is refused`() {
        assertThat(listOf(null, "", " Paper ", "paper-fast").map { ExecutionPreset.fromConfig(it) })
            .containsOnly(ExecutionPreset.PAPER_FAST)
        assertThat(listOf("mt5", "MT5-SIM", "mt5-basic").map { ExecutionPreset.fromConfig(it) })
            .containsOnly(ExecutionPreset.MT5_BASIC)
        assertThat(listOf("mt5-realistic", "realistic").map { ExecutionPreset.fromConfig(it) })
            .containsOnly(ExecutionPreset.MT5_REALISTIC)
        assertThat(ExecutionPreset.fromConfig("stress")).isEqualTo(ExecutionPreset.STRESS)
        assertThatThrownBy { ExecutionPreset.fromConfig("turbo") }
            .hasMessageContaining("unknown execution preset 'turbo'")
    }

    @Test
    fun `a partial-fill fraction outside zero to one is refused`() {
        listOf("0", "1", "1.5").forEach { f ->
            assertThatThrownBy {
                ExecutionSimulationConfig(preset = ExecutionPreset.MT5_BASIC, partialFillFraction = BigDecimal(f))
            }.hasMessageContaining("partialFillFraction must be in (0, 1)")
        }
    }

    @Test
    fun `each slippage spec builds its model and label`() {
        val base = ExecutionSimulationConfig(preset = ExecutionPreset.MT5_BASIC, slippagePoints = 3)

        assertThat(base.slippageModel()).isSameAs(ZeroSlippage)
        assertThat(base.copy(slippage = SlippageSpec.INSTRUMENT).slippageModel()).isSameAs(InstrumentSlippage)
        assertThat(base.copy(slippage = SlippageSpec.FIXED_POINTS).slippageModel())
            .isInstanceOf(FixedPointsSlippage::class.java)
        assertThat(base.copy(slippage = SlippageSpec.UNIFORM_RANDOM).slippageModel())
            .isInstanceOf(UniformRandomSlippage::class.java)
        assertThat(SlippageSpec.entries.map { base.copy(slippage = it).slippageLabel() })
            .containsExactly("zero", "instrument:slippagePoints", "fixed-points:3", "uniform-random:0..3")
    }

    @Test
    fun `rejection and partial-fill models follow the configured knobs`() {
        val plain = ExecutionSimulationConfig(preset = ExecutionPreset.MT5_BASIC)
        val stress = ExecutionSimulationConfig.defaultsFor(ExecutionPreset.STRESS, seed = null)

        assertThat(plain.rejectionModel()).isSameAs(NoBrokerRejections)
        assertThat(plain.partialFillModel()).isSameAs(FullFill)
        assertThat(stress.rejectionModel()).isInstanceOf(RejectEveryNthOrder::class.java)
        assertThat(stress.partialFillModel()).isInstanceOf(FractionalPartialFill::class.java)
        assertThat(stress.seed).isEqualTo(42L)
    }

    @Test
    fun `broker kinds map to their default presets and ids`() {
        assertThat(
            ExecutionSimulationConfig.forBrokerKind(BrokerKind.PAPER).preset,
        ).isEqualTo(ExecutionPreset.PAPER_FAST)
        assertThat(
            ExecutionSimulationConfig.forBrokerKind(BrokerKind.MT5_SIM).preset,
        ).isEqualTo(ExecutionPreset.MT5_BASIC)
        assertThat(BrokerKind.entries.map { it.id }).containsExactly("paper", "mt5-sim")
    }

    @Test
    fun `evidence states each preset's warning, fill source and venue rules`() {
        val byPreset =
            ExecutionPreset.entries.associateWith {
                ExecutionSimulationConfig
                    .defaultsFor(
                        it,
                        7L,
                    ).toEvidence()
            }

        assertThat(byPreset.getValue(ExecutionPreset.PAPER_FAST).warning).startsWith("Optimistic fills")
        assertThat(byPreset.getValue(ExecutionPreset.PAPER_FAST).venueRules).isEqualTo("none")
        assertThat(
            byPreset.getValue(ExecutionPreset.MT5_BASIC).venueRules,
        ).isEqualTo("volume step/min, price digits, bid/ask")
        assertThat(byPreset.getValue(ExecutionPreset.MT5_REALISTIC).warning).isNull()
        assertThat(byPreset.getValue(ExecutionPreset.MT5_REALISTIC).realistic).isTrue()
        val stress = byPreset.getValue(ExecutionPreset.STRESS)
        assertThat(stress.warning).startsWith("Adverse stress execution")
        assertThat(stress.venueRules).endsWith("tradeStopsLevel")
        assertThat(stress.fillPriceSource).startsWith("bid/ask")
        assertThat(stress.rejectionModel).isEqualTo("reject-every:10")
        assertThat(stress.partialFillModel).isEqualTo("fraction:0.50")
    }

    @Test
    fun `latency labels show the lane spacing and stop delay when set`() {
        val cfg =
            ExecutionSimulationConfig(
                preset = ExecutionPreset.MT5_BASIC,
                latencyMs = 5,
                orderSpacingMs = 20,
                stopLatencyMs = 9,
            )

        assertThat(cfg.latencyLabel()).isEqualTo("fixed:5ms lane:20ms")
        assertThat(cfg.toEvidence().stopLatencyModel).isEqualTo("fixed:9ms")
        assertThat(ExecutionSimulationConfig().toEvidence().stopLatencyModel).isEqualTo("on-trigger")
    }

    @Test
    fun `negative timings, sizes and counts are refused by name`() {
        val mt5 = ExecutionPreset.MT5_BASIC
        assertThatThrownBy { ExecutionSimulationConfig(preset = mt5, latencyMs = -1) }.hasMessageContaining("latencyMs")
        assertThatThrownBy { ExecutionSimulationConfig(preset = mt5, orderSpacingMs = -1) }
            .hasMessageContaining("orderSpacingMs")
        assertThatThrownBy { ExecutionSimulationConfig(preset = mt5, stopLatencyMs = -1) }
            .hasMessageContaining("stopLatencyMs")
        assertThatThrownBy { ExecutionSimulationConfig(preset = mt5, candleCloseGraceMs = -1) }
            .hasMessageContaining("candleCloseGraceMs")
        assertThatThrownBy { ExecutionSimulationConfig(preset = mt5, heartbeatIntervalMs = 0) }
            .hasMessageContaining("heartbeatIntervalMs")
        assertThatThrownBy { ExecutionSimulationConfig(preset = mt5, slippagePoints = -1) }
            .hasMessageContaining("slippagePoints")
        assertThatThrownBy {
            ExecutionSimulationConfig(
                preset = mt5,
                rejectEvery = 0,
            )
        }.hasMessageContaining("rejectEvery")
    }

    @Test
    fun `the paper preset names each knob it cannot model`() {
        assertThatThrownBy { ExecutionSimulationConfig(latencyMs = 5) }.hasMessageContaining("does not model latency;")
        assertThatThrownBy { ExecutionSimulationConfig(stopLatencyMs = 5) }.hasMessageContaining("model stop latency;")
        assertThatThrownBy {
            ExecutionSimulationConfig(
                orderSpacingMs = 5,
            )
        }.hasMessageContaining("model order spacing;")
        assertThatThrownBy { ExecutionSimulationConfig(rejectEvery = 3) }.hasMessageContaining("model reject-every;")
        assertThatThrownBy { ExecutionSimulationConfig(partialFillFraction = BigDecimal("0.5")) }
            .hasMessageContaining("model partial fill;")
    }
}
