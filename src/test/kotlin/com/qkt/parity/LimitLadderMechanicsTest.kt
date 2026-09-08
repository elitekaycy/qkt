package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

/**
 * How a resting limit ladder behaves, pinned so a grid or martingale backtest can be trusted.
 *
 * A live capture of a ten-level ladder replayed with five legs filled where the venue filled
 * four, which looked at first like the replay inventing a fill. These cases separate the two
 * candidate explanations. The answer matters: one would be an engine defect, the other is a
 * property every ladder strategy has to be sized around.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LimitLadderMechanicsTest {
    @org.junit.jupiter.api.BeforeAll
    fun quietEngineLogs() = QuietEngineLogs.silence()

    @org.junit.jupiter.api.AfterAll
    fun restoreEngineLogs() = QuietEngineLogs.restore()

    private fun ladder(
        tempDir: Path,
        id: String,
        levels: Int = 4,
    ): Path {
        val layers =
            (1 until levels).joinToString(",\n           ") { "0.01 LIMIT AT entry - ${it * 2}" }
        val path = tempDir.resolve("$id.qkt")
        Files.writeString(
            path,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close >= 100 AND POSITION.x = 0 AND TRADES.today = 0
              THEN BUY x STACK [
                     0.01,
                     $layers
                   ]
                   BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 50 }
            """.trimIndent(),
        )
        return path
    }

    private fun run(
        path: Path,
        closes: List<String>,
        expected: Int,
    ) = GeneratedStrategyReplay.assertTickBarAndLiveParity(
        path = path,
        closes = closes,
        expectedTradeCount = expected,
        startingBalance = BigDecimal("100000"),
        runawayMaxRoundTrips = 500,
    )

    @Test
    fun `one tick through several levels fills each of them, at the price it gapped to`(
        @TempDir tempDir: Path,
    ) {
        // Seed at 100, rungs at 98, 96, 94. Price then jumps straight to 93, through all three.
        // A resting buy limit fills at its level or better, so a gap to 93 fills all three there
        // -- that is the venue's own semantic, not replay being generous, and tick and bar agree.
        val path = ladder(tempDir, "gap_through_levels")
        val result = run(path, listOf("100", "100", "93", "93"), expected = 4)
        val entries = result.backtest.trades.filterNot { it.orderId.endsWith("-tp") || it.orderId.endsWith("-sl") }
        assertThat(entries).hasSize(4)
        assertThat(entries.drop(1).map { it.price }).containsOnly("93")
    }

    @Test
    fun `price that walks down fills each rung at its own level`(
        @TempDir tempDir: Path,
    ) {
        // The same ladder against a tape that steps through the levels rather than gapping.
        // Each rung fills exactly at its own price.
        val path = ladder(tempDir, "walk_down_levels")
        val result = run(path, listOf("100", "100", "98", "96", "94", "94"), expected = 4)
        val entries = result.backtest.trades.filterNot { it.orderId.endsWith("-tp") || it.orderId.endsWith("-sl") }
        assertThat(entries.map { it.price }).containsExactly("100", "98", "96", "94")
    }

    @Test
    fun `a rung the tape never reaches does not fill`(
        @TempDir tempDir: Path,
    ) {
        // Price bottoms at 96, so the 94 rung stays resting and the stack ends three legs deep.
        val path = ladder(tempDir, "unreached_rung")
        val result = run(path, listOf("100", "100", "98", "96", "96"), expected = 3)
        val entries = result.backtest.trades.filterNot { it.orderId.endsWith("-tp") || it.orderId.endsWith("-sl") }
        assertThat(entries.map { it.price }).containsExactly("100", "98", "96")
    }

    @Test
    fun `the whole ladder hangs off the seed fill, so a seed two points away changes the leg count`(
        @TempDir tempDir: Path,
    ) {
        // This is the property behind the live-versus-replay difference, and it is by design:
        // every rung is priced `entry - d` from the SEED FILL, not from the signal price. A
        // market seed cannot be guaranteed to fill at the same price live and in replay -- a
        // couple of points of ordinary entry drift is normal -- and that drift shifts all ten
        // rungs with it. Against a tape that bottoms between two rungs, the shifted ladder
        // reaches one more level than the unshifted one.
        //
        // Nothing here is a fill the engine invented: both runs fill exactly the rungs the tape
        // reaches. It means a ladder backtest predicts leg COUNT only as precisely as it predicts
        // the seed fill, which is why a ladder must be sized to survive one rung either way.
        val path = ladder(tempDir, "seed_anchor_shift")
        // seed fills at 100 -> rungs 98, 96, 94; tape bottoms at 95 -> seed + 98 + 96 = 3 legs
        val low = run(path, listOf("100", "100", "95", "95"), expected = 3)
        assertThat(
            low.backtest.trades
                .filterNot { it.orderId.endsWith("-tp") || it.orderId.endsWith("-sl") }
                .map { it.price },
        ).containsExactly("100", "95", "95")

        // seed fills two points higher at 102 -> rungs 100, 98, 96; the same 95 bottom now
        // reaches all three
        val shifted = ladder(tempDir, "seed_anchor_shift_high")
        val high =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = shifted,
                closes = listOf("102", "102", "95", "95"),
                expectedTradeCount = 4,
                startingBalance = BigDecimal("100000"),
                runawayMaxRoundTrips = 500,
            )
        assertThat(
            high.backtest.trades
                .filterNot { it.orderId.endsWith("-tp") || it.orderId.endsWith("-sl") }
                .map { it.price },
        ).containsExactly("102", "95", "95", "95")
    }

    @Test
    fun `a ten level ladder holds tick bar and live parity all the way down`(
        @TempDir tempDir: Path,
    ) {
        // The shape the live run used: ten rungs, walked step by step. Every replay path agrees.
        val path = ladder(tempDir, "ten_level_ladder", levels = 10)
        val closes = listOf("100", "100") + (1..9).map { (100 - it * 2).toString() } + listOf("82")
        val result = run(path, closes, expected = 10)
        val entries = result.backtest.trades.filterNot { it.orderId.endsWith("-tp") || it.orderId.endsWith("-sl") }
        assertThat(entries.map { it.price })
            .containsExactly("100", "98", "96", "94", "92", "90", "88", "86", "84", "82")
    }
}
