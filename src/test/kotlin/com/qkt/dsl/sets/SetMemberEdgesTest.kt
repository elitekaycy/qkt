package com.qkt.dsl.sets

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.universe.SetMembership
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A member's saved rule edges across restarts: kept, so a rule that fired does not fire again, unless the session
 * cleared the strategy's edges (its position closed while the daemon was down), which then holds for members too.
 */
class SetMemberEdgesTest {
    @TempDir
    lateinit var tmp: Path

    private val ast =
        (
            Dsl.parse(
                """
                STRATEGY movers VERSION 1
                SYMBOLS
                    movers = BACKTEST:* WHERE kind = spot TOP 3 BY symbol EVERY 1m
                RULES
                    FOR EACH m IN movers DO
                      WHEN m.close > 0 THEN BUY m SIZING 1
                """.trimIndent(),
            ) as ParseResult.Success
        ).value

    private val t0 = 1_700_000_040_000L

    /** The signals of one session over the shared state: AAA joins and one bar closes above 0. */
    private fun session(edgesCleared: Boolean): List<Signal> {
        val signals = mutableListOf<Signal>()
        val restore = MemberRestore()
        restore.persistor = FileStatePersistor(tmp)
        restore.clearEdges = edgesCleared
        val hub = CandleHub()
        val ctx = testStrategyContext(strategyId = "movers")
        val instances =
            SetMemberInstances(SetMemberTemplate(ast, "movers"), hub, ctx, { signals += it }, restore) { _, _, _ ->
                emptyList()
            }
        instances.apply(SetMembership().step(listOf("AAA"), emptySet()))
        hub.feed(Tick("BACKTEST:AAA", BigDecimal("5"), t0 + 1_000L))
        hub.feed(Tick("BACKTEST:AAA", BigDecimal("5"), t0 + 60_000L))
        return signals
    }

    @Test
    fun `a member's fired edge survives a restart, and is cleared with the strategy's after a downtime close`() {
        assertThat(session(edgesCleared = false)).hasSize(1)
        assertThat(session(edgesCleared = false)).isEmpty()
        assertThat(session(edgesCleared = true)).hasSize(1)
    }

    /** The buys of one host session over the shared state, over two minutes of the rotation tape. */
    private fun hosted(downtimeClose: Boolean): Int {
        val signals = mutableListOf<Signal>()
        val host = DynamicSetFixtures.plan().strategy(emptyMap(), live = true) as DynamicSetHost
        host.bindStatePersistor("rotation", FileStatePersistor(tmp.resolve("host")))
        if (downtimeClose) host.clearRuleEdges()
        val ctx = testStrategyContext(strategyId = "rotation")
        val hub = CandleHub()
        for (k in host.declaredStreams.values.toSet() + host.retentionByKey.keys) hub.register(k, 1, "rotation")
        host.bindToHub(hub, ctx) { signals += it }
        for (tick in DynamicSetFixtures.tape(2)) {
            host.onTick(tick, ctx) { signals += it }
            hub.feed(tick)
        }
        return signals.size
    }

    @Test
    fun `a host that cleared its edges before any member joined clears each member's as it joins`() {
        assertThat(hosted(downtimeClose = false)).isEqualTo(1)
        assertThat(hosted(downtimeClose = false)).isEqualTo(0)
        assertThat(hosted(downtimeClose = true)).isEqualTo(1)
    }
}
