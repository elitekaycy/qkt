package com.qkt.cli.daemon

import com.qkt.common.FixedClock
import com.qkt.marketdata.live.tv.FakeTradingViewWebSocket
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Locks the engine-to-source convention: every call site that constructs a
 * [com.qkt.marketdata.source.MarketSource] for a strategy must hand it
 * symbols in the prefixed qkt form (`OANDA:EURUSD`, not bare `EURUSD`).
 *
 * Uses the real [TradingViewMarketSource] (with a fake WebSocket) so the
 * source's `supports`/`liveTicks` require-checks run on real values. The
 * fake sources in [StrategyHandleTest] short-circuit those checks with
 * `supports = true`, which is why the original bare-vs-prefixed bug
 * shipped — this test exists to make a regression loud at unit-test time.
 */
class StrategyHandleTradingViewDeployTest {
    @Test
    fun `deploy succeeds when engine passes prefixed symbols to real TradingViewMarketSource`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { _ ->
                    TradingViewMarketSource(
                        webSocket = FakeTradingViewWebSocket(),
                        clock = FixedClock(time = 0L),
                    )
                },
            )
        val file = Path.of("src/test/resources/cli/tradingview_strategy.qkt")

        val handle = factory.create("tv-smoke", file, false)
        try {
            assertThat(handle.isRunning()).isTrue
        } finally {
            handle.close()
        }
    }
}
