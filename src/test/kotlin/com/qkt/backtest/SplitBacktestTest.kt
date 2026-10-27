package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionSource
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A stock CFD held across a 10-for-1 split (ex 2026-06-09, raw 1000 then 100) on Exness's 06:00-15:45 New York
 * session: the run reads one adjusted series, so the split is neither a 90% loss nor a 90% fall in its bars, and a
 * dividend paid before the split is per adjusted share. A run ending before the split reads raw prices, and no
 * position is opened in the days before the split ([com.qkt.risk.rules.SplitWindow]).
 */
class SplitBacktestTest {
    private val nvda =
        InstrumentMeta(
            qktSymbol = "EXNESS:NVDA",
            contractSize = BigDecimal(100),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = BigDecimal(10),
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
            currency = "USD",
            category = "Stocks",
        )
    private val split = CorporateAction.Split(LocalDate.parse("2026-06-09"), BigDecimal(10))
    private val dividend = CorporateAction.Dividend(LocalDate.parse("2026-06-08"), BigDecimal("1.00"), "USD")
    private val calendar = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse")

    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) = nvda.takeIf { qktSymbol == it.qktSymbol }

            override fun corporateActions() =
                CorporateActionSource { if (it == "EXNESS:NVDA") listOf(dividend, split) else emptyList() }
        }

    private fun session(
        date: String,
        price: String,
        spread: String,
    ): List<Tick> {
        val open = Instant.parse("${date}T10:00:00Z").toEpochMilli()
        val p = Money.of(price)
        val half = Money.of(spread)
        return (0 until 585).map { m -> Tick("EXNESS:NVDA", p, open + m * 60_000L, bid = p - half, ask = p + half) }
    }

    private val ticks =
        session("2026-06-01", "1000", "0.50") + session("2026-06-08", "1000", "0.50") +
            session("2026-06-09", "100", "0.05")

    private val source =
        object : MarketSource {
            override val name = "split-ticks"
            override val capabilities = setOf(MarketSourceCapability.TICKS)

            override fun supports(symbol: String) = symbol == "EXNESS:NVDA"

            override fun ticks(
                symbol: String,
                range: TimeRange,
            ) = ticks.asSequence().filter {
                it.symbol == symbol &&
                    it.timestamp in range.from.toEpochMilli() until range.to.toEpochMilli()
            }
        }

    private val buyThenClose =
        "  WHEN x.close > 0 AND POSITION.x = 0 AND TRADES.today = 0 AND NOW.date_utc < 20613\n" +
            "  THEN BUY x SIZING 0.01\n" +
            "  WHEN NOW.date_utc = 20613 AND POSITION.x > 0\n  THEN CLOSE x"

    private fun run(
        to: String,
        closes: MutableList<BigDecimal> = ArrayList(),
        rules: String = buyThenClose,
    ) = Backtest
        .fromSource(
            strategies =
                listOf(
                    "split" to
                        compile("STRATEGY split VERSION 1\nSYMBOLS\n  x = EXNESS:NVDA EVERY 1m\nRULES\n$rules"),
                ),
            calendar = calendar,
            source = source,
            request =
                MarketRequest(
                    symbols = listOf("EXNESS:NVDA"),
                    from = Instant.parse("2026-06-01T00:00:00Z"),
                    to = Instant.parse(to),
                ),
            candleWindow = TimeWindow.ONE_MINUTE,
            instruments = registry,
            startingBalance = BigDecimal(10_000),
            brokerKind = BrokerKind.MT5_SIM,
            preCandle = { c: Candle -> closes += c.close },
        ).run()

    @Test
    fun `a holding across the split reads one series and loses only its spread`() {
        val closes = ArrayList<BigDecimal>()
        val result = run("2026-06-10T00:00:00Z", closes)
        assertThat(closes).isNotEmpty.allSatisfy { assertThat(it).isBetween(BigDecimal(99), BigDecimal(101)) }
        val fills = result.trades.map { it.trade.price }
        assertThat(fills).hasSize(2).allSatisfy { assertThat(it).isBetween(BigDecimal(99), BigDecimal(101)) }
        // One share: the round trip costs its spread, a fraction of a dollar, not the $900 a raw series would book.
        assertThat(result.trades.sumOf { it.realized }.abs()).isLessThan(BigDecimal.ONE)
    }

    @Test
    fun `a dividend before the split is paid per adjusted share`() {
        // Held from 2026-06-01 into the 2026-06-08 ex-date: one adjusted share is a tenth of a raw one.
        assertThat(run("2026-06-10T00:00:00Z").global.dividendsPaid).isEqualByComparingTo("-0.10")
    }

    @Test
    fun `a run ending before the split reads raw prices and pays the raw dividend`() {
        val closes = ArrayList<BigDecimal>()
        val result = run("2026-06-09T00:00:00Z", closes)
        assertThat(closes).isNotEmpty.allSatisfy { assertThat(it).isEqualByComparingTo("1000") }
        assertThat(result.global.dividendsPaid).isEqualByComparingTo("-1.00")
    }

    @Test
    fun `no new position is opened in the days before a split, in a backtest as live`() {
        val result =
            run(
                "2026-06-10T00:00:00Z",
                rules = "  WHEN NOW.date_utc = 20612 AND POSITION.x = 0\n  THEN BUY x SIZING 0.01",
            )
        assertThat(result.trades).isEmpty()
    }
}
