package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.parity.DslParityState.TradeState
import com.qkt.parity.DslParityState.tradeState
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A set member's order ids across two restarts, through an account that refuses a reused client order id as a venue
 * gateway's idempotency does. Member A trades and leaves in the first session; the second session, in which only B
 * trades, must not forget A's last id; in the third A rejoins and its entry carries an id it never sent.
 *
 * A leads the volume ranking until t0+2m and from t0+6m30s, B in between. A buys at 2m and closes at 3m; B buys at
 * 6m in the second session (t0+4m to t0+6m30s) and holds; A rejoins at 7m in the third and buys at 8m.
 */
class LiveSetSecondRestartIdsTest {
    @TempDir
    lateinit var tmp: Path

    private val source =
        DynamicSetFixtures.source
            .replace("kind = event TOP 2 BY symbol DESC DYNAMIC", "kind = event AND volume > 0 TOP 1 BY volume")

    private fun tick(
        symbol: String,
        price: String,
        atMs: Long,
        volume: Int,
    ) = Tick("BACKTEST:$symbol", BigDecimal(price), atMs, BigDecimal(volume))

    private val ticks: List<Tick> =
        (0 until 10 * 6).flatMap { i ->
            val ts = T0 + i * 10_000L
            val aLeads = ts < T0 + 2 * MINUTE || ts >= T0 + 6 * MINUTE + 30_000L
            val aPrice = if (ts >= T0 + 2 * MINUTE && ts < T0 + 4 * MINUTE) "99" else "101"
            val bPrice = if (ts < T0 + 4 * MINUTE) "99" else "101"
            listOf(tick("A", aPrice, ts, if (aLeads) 5 else 1), tick("B", bPrice, ts, if (aLeads) 1 else 5))
        }

    private val seen = HashSet<String>()

    /** An account that keeps no positions across sessions and refuses a client order id it has seen in any of them. */
    private val account: BrokerFactory = { bus, clock, prices, _, _ ->
        val paper = PaperBroker(bus, clock, prices)
        object : Broker by paper {
            override fun isAccountWide(symbol: String) = true

            override fun submit(request: OrderRequest): SubmitAck =
                if (seen.add(request.id)) paper.submit(request) else SubmitAck(request.id, null, false, "duplicate id")
        }
    }

    /** One live session over the ticks in [fromMs, toMs), sharing its state directory with the others. */
    private fun session(
        fromMs: Long,
        toMs: Long,
        trades: MutableList<TradeState>,
    ) {
        val plan = DynamicSetFixtures.plan(source)
        val clock = FixedClock(fromMs)
        val handle =
            LiveSession(
                strategies = listOf("rotation" to plan.strategy(emptyMap(), live = true)),
                haltRules = emptyList(),
                source = LiveSetVenue(ticks.filter { it.timestamp in fromMs until toMs }, emptyMap()),
                symbols = plan.ast.streams.map { it.qktSymbol },
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                onTrade = { trade, realized, owner -> trades += tradeState(owner, trade, realized) },
                initialBalance = BigDecimal("10000"),
                brokerFactories = mapOf("backtest" to account),
                persistor = FileStatePersistor(tmp.resolve("state")),
                busOverride = EventBus(clock, MonotonicSequenceGenerator()),
            ).start()
        check(handle.awaitTermination(Duration.ofSeconds(20))) { "live session did not terminate" }
    }

    @Test
    fun `a member that left before one restart and rejoins after the next still sends a new id`() {
        val trades = mutableListOf<TradeState>()
        BacktestLiveParityFixtures.withQuietParityLogs {
            session(T0, T0 + 4 * MINUTE, trades)
            session(T0 + 4 * MINUTE, T0 + 6 * MINUTE + 30_000L, trades)
            session(T0 + 6 * MINUTE + 30_000L, T0 + 10 * MINUTE, trades)
        }

        assertThat(trades.map { it.symbol to it.side }).containsExactly(
            "BACKTEST:A" to "BUY",
            "BACKTEST:A" to "SELL",
            "BACKTEST:B" to "BUY",
            "BACKTEST:A" to "BUY",
        )
        assertThat(trades.map { it.orderId }.distinct()).hasSize(4)
    }
}
