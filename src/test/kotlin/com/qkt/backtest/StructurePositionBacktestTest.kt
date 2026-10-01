package com.qkt.backtest

import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Structure positions on the four real book snapshots of `btc-usdc-book-live4` (see its PROVENANCE
 * and [StructureBacktestTest]): one live structure per alias, its fields, and `CLOSE`.
 */
class StructurePositionBacktestTest {
    private val open =
        "OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { SELL PUT DELTA 0.25 DTE 7 TO 30, BUY PUT DELTA 0.10 SAME EXPIRY }"

    private fun run(
        dir: Path,
        rules: String,
    ) = FuturesFixtureRun
        .run(
            dir,
            "btc-usdc-book-live4",
            "STRATEGY spread VERSION 1\nSYMBOLS\n    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1m,\n" +
                "    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.7d EVERY 1m\nRULES\n$rules\n",
            from = "2026-10-01",
            to = "2026-10-02",
            resources = "options",
        ).first

    @Test
    fun `a second OPEN of a live alias fires nothing`(
        @TempDir dir: Path,
    ) {
        val result =
            run(
                dir,
                "    WHEN iv.close > 0\n    THEN $open SIZING 0.1\n" +
                    "    WHEN iv.close > 0.01\n    THEN $open SIZING 0.2\n",
            )

        assertThat(result.trades).hasSize(2).allMatch { it.trade.quantity.compareTo(BigDecimal("0.1")) == 0 }
    }
}
