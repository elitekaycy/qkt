package com.qkt.backtest

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A structure sized under the venue's minimum trades nothing instead of rounding up to the minimum. */
class StructureBelowMinimumBacktestTest {
    private fun run(
        dir: Path,
        sizing: String,
    ) = FuturesFixtureRun
        .run(
            dir,
            "btc-usdc-book-live4",
            "STRATEGY spread VERSION 1\nSYMBOLS\n    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1m,\n" +
                "    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.7d EVERY 1m\nRULES\n    WHEN iv.close > 0\n" +
                "    THEN OPEN ps = OPTIONS ON DERIBIT:BTC_USDC " +
                "{ SELL PUT DELTA 0.25 DTE 7 TO 30, BUY PUT DELTA 0.10 SAME EXPIRY } SIZING $sizing\n",
            from = "2026-10-01",
            to = "2026-10-02",
            flags = emptyList(),
            resources = "options",
        ).first

    @Test
    fun `a size below the venue minimum opens nothing`(
        @TempDir dir: Path,
    ) {
        assertThat(run(dir, "0.001").trades).isEmpty()
    }
}
