package com.qkt.cli.bot

import com.qkt.cli.PerStrategyRisk
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.createLiveSession
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotSessionIdentities
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.IntentLedger
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BotLivePerStrategyRiskTest {
    @Test
    fun `per_strategy_risk on one of several identities is refused naming it`(
        @TempDir tmp: Path,
    ) {
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.toString()),
                marketSourceProvider = { NullMarketSource },
                perStrategyRisk = mapOf("brain" to PerStrategyRisk(maxOpenPositions = 1)),
            )
        val streams = BotSessionStreams(listOf("EXNESS:XAUUSD"), listOf("1m"))
        val history = BarHistory(100)
        val identities = BotSessionIdentities.compile(streams, listOf("brain", "trend"), IntentLedger(), history)

        assertThatThrownBy {
            factory.createLiveSession(
                name = "live-risk",
                strategies =
                    identities.map { (id, s) -> id to s } + (BotSessionRecorder.ID to BotSessionRecorder(history)),
                symbols = streams.symbols,
                candleWindow = streams.baseWindow,
                ignoreMismatches = false,
                onTrade = { _, _, _ -> },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("per_strategy_risk binds one identity per session; run brain in a session of its own")
    }
}
