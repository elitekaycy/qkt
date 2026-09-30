package com.qkt.backtest

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The accounting identity for any trading on a continuous stream that ends flat, on the real
 * 2024-09-19 roll (`btcusdt-rolls`): the engine's realized P&L equals the cash of every contract
 * trade — fills and roll legs — less every fee. Positions are scaled in at two prices, partly
 * closed before the roll, added to after it on the new contract, then closed.
 */
class ContinuousFuturesIdentityTest {
    private val taker = BigDecimal("0.0005")

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun strategy(
        open: String,
        close: String,
    ) = """
        STRATEGY ident VERSION 1
        SYMBOLS
            btc = BINANCE_UM:BTCUSDT@front EVERY 15m
        RULES
            WHEN NOW.epoch_ms >= ${ms("2024-09-18T02:00:00Z")} THEN $open btc SIZING 0.01
            WHEN NOW.epoch_ms >= ${ms("2024-09-18T12:00:00Z")} THEN $open btc SIZING 0.02
            WHEN NOW.epoch_ms >= ${ms("2024-09-19T00:00:00Z")} THEN $close btc SIZING 0.01
            WHEN NOW.epoch_ms >= ${ms("2024-09-19T16:00:00Z")} THEN $open btc SIZING 0.01
            WHEN NOW.epoch_ms >= ${ms("2024-09-20T12:00:00Z")} THEN $close btc SIZING 0.03
    """

    private fun assertIdentity(
        dir: Path,
        open: String,
        close: String,
        carried: String,
    ) {
        val (result, _) =
            FuturesFixtureRun.run(
                dir,
                "btcusdt-rolls",
                strategy(open, close),
                from = "2024-09-18",
                to = "2024-09-21",
                rootLines = "    slippageTicks: 1\n",
                flags = listOf("--slippage", "instrument"),
            )

        val fills = result.contractFills
        assertThat(fills).hasSize(5)
        assertThat(result.rolls.single().quantity).isEqualByComparingTo(carried)
        val contractQty =
            fills.fold(BigDecimal.ZERO) { q, f ->
                if (f.side ==
                    Side.BUY
                ) {
                    q.add(f.quantity)
                } else {
                    q.subtract(f.quantity)
                }
            }
        assertThat(contractQty).isEqualByComparingTo("0")

        val fillCash =
            fills.fold(BigDecimal.ZERO) { cash, f ->
                f.contractPrice.multiply(f.quantity).let {
                    if (f.side ==
                        Side.SELL
                    ) {
                        cash.add(it)
                    } else {
                        cash.subtract(it)
                    }
                }
            }
        val rollCash =
            result.rolls.fold(
                BigDecimal.ZERO,
            ) { cash, r -> cash.add(r.quantity.multiply(r.fromFill.subtract(r.toFill))) }
        val notional =
            fills
                .fold(BigDecimal.ZERO) { n, f -> n.add(f.contractPrice.multiply(f.quantity)) }
                .add(
                    result.rolls.fold(
                        BigDecimal.ZERO,
                    ) { n, r -> n.add(r.quantity.abs().multiply(r.fromFill.add(r.toFill))) },
                )
        val expected = fillCash.add(rollCash).subtract(notional.multiply(taker))

        val engine = result.perStrategy.values.single()
        assertThat(engine.unrealizedTotal).isEqualByComparingTo("0")
        assertThat(engine.realizedTotal).isEqualByComparingTo(expected)
    }

    @Test
    fun `a long scaled in, trimmed and added to across a roll books its contract cash exactly`(
        @TempDir dir: Path,
    ) = assertIdentity(dir, open = "BUY", close = "SELL", carried = "0.02")

    @Test
    fun `a short scaled in, trimmed and added to across a roll books its contract cash exactly`(
        @TempDir dir: Path,
    ) = assertIdentity(dir, open = "SELL", close = "BUY", carried = "-0.02")
}
