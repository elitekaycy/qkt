package com.qkt.backtest

import com.qkt.common.Side
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Four real Deribit book snapshots a minute apart (`btc-usdc-book-live4`, see its PROVENANCE): the 82000 put
 * expiring 9 Oct is quoted at mark IV 32.43, 32.44, 32.46 and 32.47 at 06:15:16, 06:16:17, 06:17:19 and
 * 06:18:20. A 1m rule reads, at each bar's close, the newest snapshot at or before it (the last bar closes at
 * the end of the data, 06:19:00). The expected delta is
 * an independent Black-76 (Python) on the 06:16:17 row (forward 84444.22) at 06:17:00, to expiry
 * 2026-10-09T08:00Z: −0.2633718596.
 */
class OptionFieldsBacktestTest {
    private val put = "DERIBIT:BTC_USDC_9OCT26_82000_P"

    private fun run(
        dir: Path,
        condition: String,
        feed: String = "",
    ) = FuturesFixtureRun
        .run(
            dir,
            "btc-usdc-book-live4",
            "STRATEGY iv VERSION 1\nSYMBOLS\n    c = $put EVERY 1m$feed\nRULES\n" +
                "    WHEN $condition AND POSITION.c = 0\n    THEN BUY c SIZING 0.1\n",
            from = "2026-10-01",
            to = "2026-10-02",
            resources = "options",
        ).first

    private fun decidedAt(
        dir: Path,
        condition: String,
    ): List<Long> =
        run(Files.createDirectories(dir), condition)
            .causality!!
            .ruleDecisions
            .filter { it.conditionResult }
            .map { it.candle.endTime }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `each bar close reads the snapshot at or before it, never the one that closed the bar`(
        @TempDir dir: Path,
    ) {
        assertThat(decidedAt(dir.resolve("a"), "c.iv > 32.435 AND c.iv < 32.445"))
            .containsExactly(ms("2026-10-01T06:17:00Z"))
        assertThat(decidedAt(dir.resolve("b"), "c.iv > 32.455 AND c.iv < 32.465"))
            .containsExactly(ms("2026-10-01T06:18:00Z"))
        assertThat(decidedAt(dir.resolve("c"), "c.iv > 32.465")).containsExactly(ms("2026-10-01T06:19:00Z"))
    }

    @Test
    fun `the delta read is black-76 on the snapshot's mark iv and forward, and the rule trades on it`(
        @TempDir dir: Path,
    ) {
        val result = run(dir, "c.delta > -0.26337187 AND c.delta < -0.26337185")

        assertThat(
            result.causality!!
                .ruleDecisions
                .filter { it.conditionResult }
                .map { it.candle.endTime },
        ).containsExactly(ms("2026-10-01T06:17:00Z"))
        assertThat(result.trades.map { it.trade.side to it.trade.symbol }).containsExactly(Side.BUY to put)
    }

    @Test
    fun `a backtest reading the greeks of a feed that is no option contract is refused before it runs`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { run(dir, "chain.delta < 0", feed = ",\n    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1m") }
            .hasMessageContaining("Greeks of OPTIONS:DERIBIT.BTC_USDC but it is not a catalogued option contract")
    }
}
