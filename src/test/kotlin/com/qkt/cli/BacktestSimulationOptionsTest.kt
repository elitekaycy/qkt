package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionPreset
import com.qkt.backtest.SlippageSpec
import com.qkt.broker.PositionAccountingMode
import com.qkt.cli.BacktestContext.Companion.SetupError
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestSimulationOptionsTest {
    private val defaults = Config.load(Path.of("build/no-such-qkt.config.yaml"))

    private fun args(vararg argv: String) = Args(arrayOf("backtest", "s.qkt", *argv))

    private fun execution(vararg argv: String) =
        BacktestSimulationOptions.executionConfig(args(*argv), defaults, BrokerKind.MT5_SIM)

    @Test
    fun `fx symbols from the command line join the configured conversions`() {
        val cfg = BacktestSimulationOptions.accountingConfig(args("--fx-symbol", " EURUSD = EXNESS:EURUSD "), defaults)

        assertThat(cfg.symbols).containsEntry("EURUSD", "EXNESS:EURUSD")
    }

    @Test
    fun `an fx symbol without both sides of the equals sign is refused`() {
        listOf("EURUSD", "=EXNESS:EURUSD", "EURUSD=").forEach { token ->
            assertThatThrownBy { BacktestSimulationOptions.accountingConfig(args("--fx-symbol", token), defaults) }
                .isInstanceOf(SetupError::class.java)
                .hasMessage("bad --fx-symbol '$token'; expected PAIR=QKT_SYMBOL")
        }
    }

    @Test
    fun `chaos selects the stress preset and cannot be combined with an execution preset`() {
        assertThat(execution("--chaos").preset).isEqualTo(ExecutionPreset.STRESS)
        assertThatThrownBy { execution("--chaos", "--execution", "mt5-basic") }
            .isInstanceOf(SetupError::class.java)
            .hasMessage("--chaos cannot be combined with --execution")
    }

    @Test
    fun `latencies read milliseconds and seconds and refuse anything else`() {
        assertThat(execution("--execution-latency", "250ms").latencyMs).isEqualTo(250L)
        assertThat(execution("--execution-latency", "fixed:1.5s").latencyMs).isEqualTo(1500L)
        assertThat(execution("--stop-latency", "40").stopLatencyMs).isEqualTo(40L)
        assertThatThrownBy { execution("--order-spacing", "soon") }
            .isInstanceOf(SetupError::class.java)
            .hasMessage("bad execution latency 'soon'")
    }

    @Test
    fun `rejections partial fills and position mode come from their flags`() {
        val cfg = execution("--reject-every", "4", "--partial-fill", "0.5", "--position-mode", " Netting ")

        assertThat(cfg.rejectEvery).isEqualTo(4)
        assertThat(cfg.partialFillFraction).isEqualByComparingTo("0.5")
        assertThat(cfg.positionMode).isEqualTo(PositionAccountingMode.NETTING)
        assertThat(execution().positionMode).isEqualTo(PositionAccountingMode.HEDGING)
        assertThatThrownBy { execution("--reject-every", "often") }
            .isInstanceOf(SetupError::class.java)
            .hasMessage("bad reject_every 'often'")
        assertThatThrownBy { execution("--position-mode", "fifo") }
            .isInstanceOf(SetupError::class.java)
            .hasMessage("unknown position mode 'fifo' (valid: netting, hedging)")
    }

    @Test
    fun `every slippage spelling maps to its model and points`() {
        mapOf(
            "none" to (SlippageSpec.ZERO to 0),
            "instrument" to (SlippageSpec.INSTRUMENT to 0),
            "fixed-points:3" to (SlippageSpec.FIXED_POINTS to 3),
            "fixed:2" to (SlippageSpec.FIXED_POINTS to 2),
            "uniform-random:5" to (SlippageSpec.UNIFORM_RANDOM to 5),
            "uniform:4" to (SlippageSpec.UNIFORM_RANDOM to 4),
        ).forEach { (raw, expected) ->
            val cfg = execution("--slippage", raw)
            assertThat(cfg.slippage to cfg.slippagePoints).withFailMessage(raw).isEqualTo(expected)
        }
        assertThatThrownBy { execution("--slippage", "fixed:lots") }
            .isInstanceOf(SetupError::class.java)
            .hasMessage("bad slippage 'fixed:lots': points must be an integer")
        assertThatThrownBy { execution("--slippage", "gaussian") }
            .isInstanceOf(SetupError::class.java)
            .hasMessage("bad slippage 'gaussian' (valid: zero, instrument, fixed-points:N, uniform:N)")
    }
}
