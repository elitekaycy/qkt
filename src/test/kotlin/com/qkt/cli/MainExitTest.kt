package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A command that fails by throwing still ends the process with a non-zero code, naming the failure. */
class MainExitTest {
    @Test
    fun `a command that throws exits non-zero naming the failure instead of escaping main`() {
        val err = ByteArrayOutputStream()

        val code =
            exitCodeOf(arrayOf("run", "s.qkt"), PrintStream(err)) {
                throw IllegalArgumentException("continuous futures streams [BINANCE_UM:BTCUSDT@front] refused")
            }

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err.toString()).contains("qkt: error: continuous futures streams [BINANCE_UM:BTCUSDT@front] refused")
    }

    @Test
    fun `a stream the live feed cannot serve is refused with its fix and no stack trace`() {
        val err = ByteArrayOutputStream()

        val code =
            exitCodeOf(arrayOf("run", "s.qkt"), PrintStream(err)) {
                throw com.qkt.marketdata.live.tv
                    .UnservableStreamException("BACKTEST:EURUSD -> OANDA:EURUSD")
            }

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err.toString().trim()).isEqualTo("qkt: error: BACKTEST:EURUSD -> OANDA:EURUSD")
    }

    @Test
    fun `a command that returns keeps its own exit code`() {
        assertThat(exitCodeOf(arrayOf("x"), PrintStream(ByteArrayOutputStream())) { 3 }).isEqualTo(3)
    }
}
