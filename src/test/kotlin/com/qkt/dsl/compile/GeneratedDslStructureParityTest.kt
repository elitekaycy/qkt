package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GeneratedDslStructureParityTest {
    @Test
    fun `for each expands into independently executable stream rules`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("for-each.qkt")
        Files.writeString(
            path,
            """
            STRATEGY generated_for_each VERSION 1
            SYMBOLS
              x = $X EVERY 1m
              y = $Y EVERY 1m
            RULES
              WHEN x.close < 0 THEN LOG "inactive"
            FOR EACH s IN [x, y] DO
              WHEN s.close = 100 AND POSITION.s = 0
              THEN BUY s SIZING 1
            """.trimIndent(),
        )

        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                candlesBySymbol =
                    mapOf(
                        X to flatCandles(X, "100", 3),
                        Y to flatCandles(Y, "100", 3),
                    ),
                window = TimeWindow.ONE_MINUTE,
                expectedTradeCount = 2,
            )

        assertThat(result.backtest.trades.map { it.symbol }).containsExactlyInAnyOrder(X, Y)
    }

    @Test
    fun `basket decision fans equal notional into constituent orders`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("basket.qkt")
        Files.writeString(
            path,
            """
            STRATEGY generated_basket VERSION 1
            SYMBOLS
              x = $X EVERY 1m
              y = $Y EVERY 1m
              pair = BASKET EQUAL_WEIGHT [x, y] EVERY 1m
            RULES
              WHEN pair.close >= 101 AND POSITION.x = 0 AND POSITION.y = 0
              THEN BUY pair SIZING 20 USD
            """.trimIndent(),
        )

        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                candlesBySymbol =
                    mapOf(
                        X to closeCandles(X, listOf("100", "101", "101", "101")),
                        Y to closeCandles(Y, listOf("200", "202", "202", "202")),
                    ),
                window = TimeWindow.ONE_MINUTE,
                expectedTradeCount = 2,
                instruments = instruments(),
            )

        val quantityBySymbol = result.backtest.trades.associate { it.symbol to it.quantity }
        val xQuantity = BigDecimal(quantityBySymbol.getValue(X))
        val yQuantity = BigDecimal(quantityBySymbol.getValue(Y))
        assertThat(quantityBySymbol.keys).containsExactlyInAnyOrder(X, Y)
        assertThat(xQuantity).isEqualByComparingTo("0.09900990")
        assertThat(yQuantity).isEqualByComparingTo("0.04950495")
    }

    @Test
    fun `schedule fires once when the engine clock crosses its deadline`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("schedule.qkt")
        Files.writeString(
            path,
            """
            STRATEGY generated_schedule VERSION 1
            SYMBOLS x = $X EVERY 1m
            SCHEDULE
              AT 00:02 UTC THEN BUY x SIZING 1
            RULES
              WHEN x.close < 0 THEN LOG "inactive"
            """.trimIndent(),
        )

        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                candlesBySymbol = mapOf(X to flatCandles(X, "100", 5)),
                window = TimeWindow.ONE_MINUTE,
                expectedTradeCount = 1,
            )

        val trade = result.backtest.trades.single()
        assertThat(trade.timestamp).isEqualTo(120_000L)
    }

    @Test
    fun `hourly schedule replays each crossed occurrence`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("schedule-hourly.qkt")
        Files.writeString(path, scheduleSource("generated_schedule_hourly", "EVERY HOUR AT :02"))

        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                candlesBySymbol = mapOf(X to flatCandles(X, "100", 65)),
                window = TimeWindow.ONE_MINUTE,
                expectedTradeCount = 2,
            )

        val timestamps = result.backtest.trades.map { it.timestamp }
        assertThat(timestamps).containsExactly(120_000L, 3_720_000L)
    }

    @Test
    fun `daily schedule fires at the declared wall clock`(
        @TempDir tempDir: Path,
    ) {
        val path = tempDir.resolve("schedule-daily.qkt")
        Files.writeString(path, scheduleSource("generated_schedule_daily", "EVERY DAY AT 00:02 UTC"))

        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                candlesBySymbol = mapOf(X to flatCandles(X, "100", 5)),
                window = TimeWindow.ONE_MINUTE,
                expectedTradeCount = 1,
            )

        val trade = result.backtest.trades.single()
        assertThat(trade.timestamp).isEqualTo(120_000L)
    }

    @Test
    fun `weekday schedule skips the weekend gap`(
        @TempDir tempDir: Path,
    ) {
        val friday =
            LocalDate
                .of(1970, 1, 2)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val monday = friday + 3 * DAY_MS
        val path = tempDir.resolve("schedule-weekday.qkt")
        Files.writeString(path, scheduleSource("generated_schedule_weekday", "EVERY WEEKDAY AT 00:02 UTC"))

        val result =
            GeneratedStrategyReplay.assertTickBarAndLiveParity(
                path = path,
                candlesBySymbol =
                    mapOf(
                        X to
                            candlesAt(
                                X,
                                listOf(
                                    friday,
                                    friday + MINUTE_MS,
                                    friday + 2 * MINUTE_MS,
                                    monday,
                                    monday + 2 * MINUTE_MS,
                                ),
                            ),
                    ),
                window = TimeWindow.ONE_MINUTE,
                expectedTradeCount = 2,
            )

        assertThat(result.backtest.trades.map { it.timestamp })
            .containsExactly(friday + 2 * MINUTE_MS, monday + 2 * MINUTE_MS)
    }

    private companion object {
        const val X = "BACKTEST:X"
        const val Y = "BACKTEST:Y"
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L

        fun flatCandles(
            symbol: String,
            close: String,
            count: Int,
        ): List<Candle> = closeCandles(symbol, List(count) { close })

        fun closeCandles(
            symbol: String,
            closes: List<String>,
        ): List<Candle> =
            closes.mapIndexed { index, close ->
                val price = BigDecimal(close)
                val start = index * TimeWindow.ONE_MINUTE.durationMs
                Candle(
                    symbol = symbol,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = BigDecimal.ONE,
                    startTime = start,
                    endTime = start + TimeWindow.ONE_MINUTE.durationMs,
                )
            }

        fun candlesAt(
            symbol: String,
            startTimes: List<Long>,
        ): List<Candle> =
            startTimes.map { start ->
                val price = BigDecimal("100")
                Candle(
                    symbol = symbol,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = BigDecimal.ONE,
                    startTime = start,
                    endTime = start + MINUTE_MS,
                )
            }

        fun scheduleSource(
            name: String,
            trigger: String,
        ): String =
            """
            STRATEGY $name VERSION 1
            SYMBOLS x = $X EVERY 1m
            SCHEDULE
              $trigger THEN BUY x SIZING 1
            RULES
              WHEN x.close < 0 THEN LOG "inactive"
            """.trimIndent()

        fun instruments(): InstrumentRegistry =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String): InstrumentMeta =
                    InstrumentMeta(
                        qktSymbol = qktSymbol,
                        contractSize = BigDecimal.ONE,
                        volumeStep = BigDecimal("0.00000001"),
                        volumeMin = BigDecimal("0.00000001"),
                        volumeMax = null,
                        pointSize = BigDecimal("0.00000001"),
                        digits = 8,
                        tradeStopsLevelPoints = 0,
                    )
            }
    }
}
