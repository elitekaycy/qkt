package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.observe.OrderJournal
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LiveSessionTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val day14 = Instant.parse("2024-01-14T00:00:00Z").toEpochMilli()

    private fun candle(
        close: String,
        startMs: Long,
    ): Candle =
        Candle(
            "X",
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of("1"),
            startMs,
            startMs + 60_000L,
        )

    private class CapturingStrategy : Strategy {
        val seen: MutableList<Tick> = mutableListOf()

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            seen.add(tick)
        }
    }

    @Test
    fun `start drives strategies with live ticks`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val strategy = CapturingStrategy()
        val clock = FixedClock(time = now.toEpochMilli())
        val session =
            LiveSession(
                strategies = listOf("test" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
            )

        val handle = session.start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(strategy.seen.map { it.price.compareTo(Money.of("100")) }.first()).isEqualTo(0)
        assertThat(strategy.seen).hasSize(2)
    }

    @Test
    fun `tick flood sheds oldest ticks but keeps the freshest`() {
        // 30k ticks against an engine thread blocked on its first tick: the bounded
        // inbound queue must shed the OLDEST ticks (a newer tick supersedes), never
        // grow without bound, and the newest tick must survive to be processed.
        val total = 30_000
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            (0 until total).map { i -> Tick("X", Money.of("100"), now.toEpochMilli() + i) },
        )
        val firstTickGate = java.util.concurrent.CountDownLatch(1)
        val seen =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val lastSeenTs =
            java.util.concurrent.atomic
                .AtomicLong(0)
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (seen.incrementAndGet() == 1) {
                        firstTickGate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    lastSeenTs.set(tick.timestamp)
                }
            }
        val handle =
            LiveSession(
                strategies = listOf("flood" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            ).start()

        // Wait until the flood has saturated the queue and shedding has begun.
        val deadline = System.currentTimeMillis() + 10_000
        while (handle.droppedTicks == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        firstTickGate.countDown()
        assertThat(handle.awaitTermination(Duration.ofSeconds(20))).isTrue()

        assertThat(handle.droppedTicks).isGreaterThan(0L)
        assertThat(seen.get()).isLessThan(total)
        // The freshest tick survived the shedding.
        assertThat(lastSeenTs.get()).isEqualTo(now.toEpochMilli() + total - 1)
    }

    @Test
    fun `a strategy exception does not kill the engine loop and raises an alert`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val seen = mutableListOf<Tick>()
        var thrown = false
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seen.add(tick)
                    if (!thrown) {
                        thrown = true
                        throw IllegalStateException("boom on first tick")
                    }
                }
            }
        val alerts = java.util.concurrent.CopyOnWriteArrayList<com.qkt.notify.NotificationEvent>()
        val recordingNotifier =
            object : com.qkt.notify.Notifier {
                override fun notify(event: com.qkt.notify.NotificationEvent) {
                    alerts.add(event)
                }

                override fun close() {}
            }
        val handle =
            LiveSession(
                strategies = listOf("boom" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                notifier = recordingNotifier,
            ).start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()

        // Tick N threw; tick N+1 must still be processed by the (alive) engine loop.
        assertThat(seen).hasSize(2)
        // The fault halts trading and raises a CRITICAL alert.
        assertThat(handle.isHalted()).isTrue()
        assertThat(
            alerts.filterIsInstance<com.qkt.notify.NotificationEvent.StrategyError>(),
        ).isNotEmpty
    }

    @Test
    fun `notification failure during engine fault is journaled durably`(
        @TempDir tmp: Path,
    ) {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(Tick("X", Money.of("100"), now.toEpochMilli())),
        )
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) = throw IllegalStateException("boom")
            }
        val throwingNotifier =
            object : com.qkt.notify.Notifier {
                override fun notify(event: com.qkt.notify.NotificationEvent) {
                    error("telegram down")
                }

                override fun close() {}
            }
        val clock = FixedClock(time = now.toEpochMilli())
        val handle =
            LiveSession(
                strategies = listOf("boom" to strategy),
                source = src,
                symbols = listOf("X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                notifier = throwingNotifier,
                journal = OrderJournal(tmp.resolve("journal"), clock),
            ).start()

        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        val journal = Files.readString(tmp.resolve("journal/boom/journal-2024-01-15.jsonl"))
        assertThat(journal).contains("\"kind\":\"notification_failed\"")
        assertThat(journal).contains("\"handler\":\"StrategyError\"")
        assertThat(journal).contains("\"reason\":\"telegram down\"")
    }

    @Test
    fun `seeded history opens the warmup gate before the first live bar`() {
        // 5 historical bars cover WARMUP 5 BARS, so the rule must fire on the FIRST
        // live closed bar — not after five more live bars. Seeding must credit the
        // gate (seed-before-bind); a cold gate here means every deploy starts with a
        // dead window the length of the warmup.
        val src = InMemoryMarketSource()
        val warmupStart = now.minusSeconds(5 * 60).toEpochMilli()
        src.seedBars(
            "EXNESS:X",
            TimeWindow.ONE_MINUTE,
            (0 until 5).map { i ->
                Candle(
                    "EXNESS:X",
                    Money.of((100 + i).toString()),
                    Money.of((100 + i).toString()),
                    Money.of((100 + i).toString()),
                    Money.of((100 + i).toString()),
                    Money.of("1"),
                    warmupStart + i * 60_000L,
                    warmupStart + (i + 1) * 60_000L,
                )
            },
        )
        src.seedLive(
            "EXNESS:X",
            listOf(
                Tick("EXNESS:X", Money.of("200"), now.toEpochMilli()),
                Tick("EXNESS:X", Money.of("201"), now.plus(Duration.ofSeconds(61)).toEpochMilli()),
            ),
        )
        val parsed =
            com.qkt.dsl.parse.Dsl.parse(
                """
                STRATEGY gatecheck VERSION 1
                SYMBOLS
                  x = EXNESS:X EVERY 1m WARMUP 5 BARS
                RULES
                  WHEN x.close > 0 THEN BUY x SIZING 1
                """.trimIndent(),
            ) as com.qkt.dsl.parse.ParseResult.Success
        val strategy =
            com.qkt.dsl.compile
                .AstCompiler()
                .compile(parsed.value)

        val signals = mutableListOf<Signal>()
        val handle =
            LiveSession(
                strategies = listOf("gatecheck" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("EXNESS:X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                onSignal = { signals.add(it) },
            ).start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()

        assertThat(signals).isNotEmpty
    }

    @Test
    fun `standalone deploy seeds the strategy starting balance from initialBalance`() {
        // No startingBalances map (that's the portfolio path) — a standalone deploy
        // must still give ACCOUNT.equity its configured balance, or % OF EQUITY
        // sizing runs on zero.
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), now.toEpochMilli())))
        val equities = mutableListOf<java.math.BigDecimal>()
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    equities.add(ctx.pnl.equity())
                }
            }
        val session =
            LiveSession(
                strategies = listOf("solo" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                initialBalance = Money.of("10000"),
            )

        val handle = session.start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(equities).isNotEmpty
        assertThat(equities.first()).isEqualByComparingTo(Money.of("10000"))
    }

    @Test
    fun `running becomes false after stop`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), now.toEpochMilli())))
        val session =
            LiveSession(
                strategies = emptyList(),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            )

        val handle = session.start()
        handle.stop()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(handle.running).isFalse()
    }

    @Test
    fun `effective warmup spec is widest among Warmable strategies`() {
        val src = InMemoryMarketSource()
        val warmupStart = now.minusSeconds(10 * 60).toEpochMilli()
        src.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            (0 until 10).map { i -> candle((100 + i).toString(), warmupStart + i * 60_000L) },
        )
        src.seedLive("X", listOf(Tick("X", Money.of("999"), now.toEpochMilli())))

        val small =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 3)

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val large =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 10)

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }

        val seenWarmup = mutableListOf<Tick>()
        val session =
            LiveSession(
                strategies = listOf("small" to small, "large" to large),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                onWarmupTick = { t -> seenWarmup.add(t) },
            )

        session.start().awaitTermination(Duration.ofSeconds(2))

        // Widest spec is 10 bars; warmup emits four OHLC ticks (O, L, H, C) per bar.
        assertThat(seenWarmup).hasSize(40)
    }

    @Test
    fun `warmupOverride beats inferred Warmable specs`() {
        val src = InMemoryMarketSource()
        val warmupStart = now.minusSeconds(50 * 60).toEpochMilli()
        src.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            (0 until 50).map { i -> candle((100 + i).toString(), warmupStart + i * 60_000L) },
        )
        src.seedLive("X", listOf(Tick("X", Money.of("999"), now.toEpochMilli())))

        val warm =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 5)

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }

        val seenWarmup = mutableListOf<Tick>()
        val session =
            LiveSession(
                strategies = listOf("warm" to warm),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                warmupOverride = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30),
                onWarmupTick = { t -> seenWarmup.add(t) },
            )
        session.start().awaitTermination(Duration.ofSeconds(2))

        // Override is 30 bars; warmup emits four OHLC ticks (O, L, H, C) per bar.
        assertThat(seenWarmup).hasSize(120)
    }

    @Test
    fun `startingBalances set the strategy equity basis`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), now.toEpochMilli())))
        val strategy = CapturingStrategy()
        val handle =
            LiveSession(
                strategies = listOf("test" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                startingBalances = mapOf("test" to java.math.BigDecimal("60000")),
            ).start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()

        val equity = handle.dailySummaryRows().first { it.strategyId == "test" }.equity
        assertThat(equity).isEqualByComparingTo(java.math.BigDecimal("60000"))
    }

    @Test
    fun `recentTrades returns the trades captured so far`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("X", Money.of("1")))
                }
            }
        val handle =
            LiveSession(
                strategies = listOf("test" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            ).start()
        handle.awaitTermination(Duration.ofSeconds(2))

        assertThat(handle.recentTrades().size).isEqualTo(2)
    }

    @Test
    fun `approved risk decision is journaled before broker submit`(
        @TempDir tmp: Path,
    ) {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), now.toEpochMilli())))
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("X", Money.of("1")))
                }
            }
        val clock = FixedClock(time = now.toEpochMilli())
        val handle =
            LiveSession(
                strategies = listOf("test" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                journal = OrderJournal(tmp.resolve("journal"), clock),
            ).start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()

        val journal = Files.readString(tmp.resolve("journal/test/journal-2024-01-15.jsonl"))
        assertThat(journal).contains("\"kind\":\"risk-approved\"")
        assertThat(journal.indexOf("\"kind\":\"risk-approved\"")).isLessThan(journal.indexOf("\"kind\":\"submit\""))
    }
}
