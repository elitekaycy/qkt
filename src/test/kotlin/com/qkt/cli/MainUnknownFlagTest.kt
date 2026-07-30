package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MainUnknownFlagTest {
    @Test
    fun `backtest rejects documented but unsupported report flag before execution`() {
        assertThat(runMain(arrayOf("backtest", "missing.qkt", "--report", "out")))
            .isEqualTo(ExitCodes.ARG_ERROR)
    }

    @Test
    fun `flags from another command are rejected`() {
        assertThat(runMain(arrayOf("backtest", "missing.qkt", "--flatten")))
            .isEqualTo(ExitCodes.ARG_ERROR)
    }
}
