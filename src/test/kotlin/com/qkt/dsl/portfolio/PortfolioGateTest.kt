package com.qkt.dsl.portfolio

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.PortfolioRule
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenRun
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PortfolioGateTest {
    private val clock = FixedClock(time = 0L)
    private val calendar = TradingCalendar.crypto()

    private fun candle(
        close: String,
        ts: Long = 0L,
        symbol: String = "BACKTEST:BTCUSDT",
    ) = Candle(
        symbol = symbol,
        open = Money.of(close),
        high = Money.of(close),
        low = Money.of(close),
        close = Money.of(close),
        volume = Money.of("1"),
        startTime = ts,
        endTime = ts + 60_000L,
    )

    private fun buildGate(
        rules: List<PortfolioRule>,
        streams: List<StreamDecl> = defaultStreams(),
        prepared: Boolean = true,
    ): PortfolioGate {
        val ast =
            PortfolioAst(
                name = "test-portfolio",
                version = 1,
                streams = streams,
                imports = rules.map { importFor(ruleAlias(it)) },
                rules = rules,
            )
        val gate = PortfolioGate(ast, clock, calendar)
        if (prepared) gate.prepare()
        return gate
    }

    private fun defaultStreams(): List<StreamDecl> =
        listOf(
            StreamDecl(alias = "btc", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
        )

    private fun importFor(alias: String): ImportClause = ImportClause(path = "$alias.qkt", alias = alias)

    private fun ruleAlias(rule: PortfolioRule): String =
        when (rule) {
            is AlwaysRun -> rule.alias
            is WhenRun -> rule.alias
        }

    @Test
    fun `initial state activates ALWAYS_RUN children only`() {
        val gate = buildGate(listOf(AlwaysRun("always"), WhenRun(BoolLit(true), "conditional")))
        val state = gate.initialState()
        assertThat(state.activeByAlias).containsEntry("always", true)
        assertThat(state.activeByAlias).doesNotContainKey("conditional")
    }

    @Test
    fun `AlwaysRun stays active after candle`() {
        val gate = buildGate(listOf(AlwaysRun("always")))
        gate.initialState()
        gate.onCandle(candle("100", ts = 0L))
        assertThat(gate.currentState().activeByAlias).containsEntry("always", true)
        assertThat(gate.currentState().changed).isFalse
    }

    @Test
    fun `WHEN candle field greater than threshold toggles child on matching close`() {
        val gate =
            buildGate(
                listOf(
                    WhenRun(
                        cond = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100"))),
                        alias = "trend",
                    ),
                ),
            )
        gate.onCandle(candle("99", ts = 0L))
        assertThat(gate.currentState().activeByAlias).doesNotContainKey("trend")

        gate.onCandle(candle("101", ts = 60_000L))
        val state = gate.currentState()
        assertThat(state.activeByAlias).containsEntry("trend", true)
        assertThat(state.changed).isTrue
    }

    @Test
    fun `WHEN rsi greater than threshold toggles after indicator warmup`() {
        val gate =
            buildGate(
                listOf(
                    WhenRun(
                        cond =
                            CmpOp(
                                Cmp.GT,
                                IndicatorCall("rsi", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("2")))),
                                NumLit(BigDecimal("50")),
                            ),
                        alias = "momentum",
                    ),
                ),
            )
        // RSI(2) needs 3 rising bars to produce a reading. First two bars are below/undefined threshold.
        gate.onCandle(candle("100", ts = 0L))
        assertThat(gate.currentState().activeByAlias).doesNotContainKey("momentum")

        gate.onCandle(candle("101", ts = 60_000L))
        assertThat(gate.currentState().activeByAlias).doesNotContainKey("momentum")

        gate.onCandle(candle("102", ts = 120_000L))
        val state = gate.currentState()
        assertThat(state.activeByAlias).containsEntry("momentum", true)
        assertThat(state.changed).isTrue
    }

    @Test
    fun `snapshot-free expression in WHEN rule compiles`() {
        val gate =
            buildGate(
                listOf(
                    WhenRun(
                        cond = CmpOp(Cmp.GT, BinaryOp(BinOp.SUB, StreamFieldRef("btc", "close"), NumLit(BigDecimal("5"))), NumLit(BigDecimal.ZERO)),
                        alias = "noop",
                    ),
                ),
            )
        assertThat(gate.currentState().activeByAlias).isEmpty()
    }

    @Test
    fun `history snapshot in WHEN rule is rejected at prepare time`() {
        val cond = CmpOp(Cmp.GT, Ref("btc.close", SnapshotTPast(5)), NumLit(BigDecimal.ZERO))
        val gate = buildGate(listOf(WhenRun(cond, alias = "fail")), prepared = false)
        assertThatThrownBy { gate.prepare() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("history snapshots")
    }

    @Test
    fun `deactivating a conditional child sets changed flag`() {
        val cond = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100")))
        val gate = buildGate(listOf(WhenRun(cond, alias = "trend")))

        gate.onCandle(candle("101", ts = 0L))
        assertThat(gate.currentState().activeByAlias).containsEntry("trend", true)

        gate.onCandle(candle("99", ts = 60_000L))
        val state = gate.currentState()
        assertThat(state.activeByAlias["trend"]).isNotEqualTo(true)
        assertThat(state.changed).isTrue
    }
}
