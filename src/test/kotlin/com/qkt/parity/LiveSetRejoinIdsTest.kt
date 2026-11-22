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
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A set member that leaves and rejoins during a live session, trading through an account that refuses a reused
 * client order id, as a venue gateway's idempotency does: its second entry carries a new id, so it is placed.
 *
 * A leads the volume ranking until t0+2m, B from t0+2m to t0+4m, A again from t0+4m. A buys at 2m with a bracket,
 * closes at 3m on its own rule while sticky, is released at 4m, rejoins at 5m and buys again at 6m.
 */
class LiveSetRejoinIdsTest {
    private val source =
        DynamicSetFixtures.source
            .replace("kind = event TOP 2 BY symbol DESC DYNAMIC", "kind = event AND volume > 0 TOP 1 BY volume")
            .replace(
                "THEN BUY m SIZING 1",
                "THEN BUY m SIZING 1 BRACKET { STOP LOSS PCT 50, TAKE PROFIT PCT 50 }",
            )

    private fun tick(
        symbol: String,
        price: String,
        atMs: Long,
        volume: Int,
    ) = Tick("BACKTEST:$symbol", BigDecimal(price), atMs, BigDecimal(volume))

    private val ticks: List<Tick> =
        (0 until 7 * 6).flatMap { i ->
            val ts = T0 + i * 10_000L
            val aLeads = ts < T0 + 2 * MINUTE || ts >= T0 + 4 * MINUTE
            val aPrice = if (ts >= T0 + 2 * MINUTE && ts < T0 + 4 * MINUTE) "99" else "101"
            listOf(tick("A", aPrice, ts, if (aLeads) 5 else 1), tick("B", "99", ts, if (aLeads) 1 else 5))
        }

    /** A paper account that refuses an order whose client order id it has seen before. */
    private val idempotent: BrokerFactory = { bus, clock, prices, _, _ ->
        val paper = PaperBroker(bus, clock, prices)
        val seen = HashSet<String>()
        object : Broker by paper {
            override fun submit(request: OrderRequest): SubmitAck =
                if (seen.add(request.id)) paper.submit(request) else SubmitAck(request.id, null, false, "duplicate id")
        }
    }

    @Test
    fun `a member that rejoins sends its next entry under a new id, so the account places it`() {
        val plan = DynamicSetFixtures.plan(source)
        val clock = FixedClock(T0)
        val trades = mutableListOf<TradeState>()
        val handle =
            BacktestLiveParityFixtures.withQuietParityLogs {
                LiveSession(
                    strategies = listOf("rejoin" to plan.strategy(emptyMap(), live = true)),
                    haltRules = emptyList(),
                    source = LiveSetVenue(ticks, emptyMap()),
                    symbols = plan.ast.streams.map { it.qktSymbol },
                    candleWindow = TimeWindow.ONE_MINUTE,
                    clock = clock,
                    calendar = TradingCalendar.crypto(),
                    onTrade = { trade, realized, owner -> trades += tradeState(owner, trade, realized) },
                    initialBalance = BigDecimal("10000"),
                    brokerFactories = mapOf("backtest" to idempotent),
                    busOverride = EventBus(clock, MonotonicSequenceGenerator()),
                ).start().also { check(it.awaitTermination(Duration.ofSeconds(20))) }
            }

        assertThat(trades.map { it.symbol to it.side }).containsExactly(
            "BACKTEST:A" to "BUY",
            "BACKTEST:A" to "SELL",
            "BACKTEST:A" to "BUY",
        )
        assertThat(trades.map { it.orderId }.distinct()).hasSize(3)
    }
}
