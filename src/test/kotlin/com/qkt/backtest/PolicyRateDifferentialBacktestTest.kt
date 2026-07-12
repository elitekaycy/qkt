package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.macro.MacroPoint
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PolicyRateDifferentialBacktestTest {
    @Test
    fun `positive differential enters and sign flip closes at the published event`(
        @TempDir tmp: Path,
    ) {
        seedGold(tmp)
        MacroSeriesStore(tmp).write(
            PolicyRateSeries.RBA_RBNZ_DIFFERENTIAL.id,
            listOf(
                MacroPoint(
                    LocalDate.of(2024, 3, 4),
                    BigDecimal("0.35"),
                    Instant.parse("2024-03-04T13:00:00Z").toEpochMilli(),
                ),
                MacroPoint(
                    LocalDate.of(2024, 3, 5),
                    BigDecimal("-0.15"),
                    Instant.parse("2024-03-05T13:00:00Z").toEpochMilli(),
                ),
            ),
        )
        val source =
            """
            STRATEGY policyCarry VERSION 1
            SYMBOLS
                gold  = BACKTEST:XAUUSD             EVERY 1m
                carry = MACRO:RBA_RBNZ_RATE_DIFF    EVERY 1d
            RULES
                WHEN carry.value > 0 AND POSITION.gold = 0
                THEN BUY gold SIZING 0.01
                WHEN carry.value <= 0 AND POSITION.gold != 0
                THEN CLOSE gold
            """.trimIndent()
        val strategy = AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value)

        val result =
            Backtest
                .fromStore(
                    strategies = listOf("policyCarry" to strategy),
                    store = DefaultDataStore(root = tmp),
                    request =
                        MarketRequest(
                            symbols = listOf("BACKTEST:XAUUSD", "MACRO:RBA_RBNZ_RATE_DIFF"),
                            from = Instant.parse("2024-03-04T12:00:00Z"),
                            to = Instant.parse("2024-03-05T14:00:00Z"),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.global.tradeCount).isEqualTo(2)
        assertThat(result.finalPositions).doesNotContainKey("XAUUSD")
        assertThat(
            result.trades
                .last()
                .trade.timestamp,
        ).isEqualTo(Instant.parse("2024-03-05T13:01:00Z").toEpochMilli())
    }

    private fun seedGold(tmp: Path) {
        val dir = tmp.resolve("symbols").resolve("XAUUSD")
        Files.createDirectories(dir)
        listOf("2024-03-04", "2024-03-05").forEachIndexed { day, date ->
            val rows =
                listOf("12:59", "13:01", "13:02").mapIndexed { index, hm ->
                    val timestamp = Instant.parse("${date}T$hm:00Z").toEpochMilli()
                    "$timestamp,XAUUSD,${2000 + day * 10 + index}.0,1.0,,,,"
                }
            Files.writeString(
                dir.resolve("$date.csv"),
                "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n" + rows.joinToString("\n") + "\n",
            )
        }
    }
}
