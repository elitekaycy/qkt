package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A roll a live session measured stays in the history when a later `qkt fetch --rolls` cannot price it from stored
 * bars (a venue with no bar source): the rebuild used to end the history before it, dropping what live traded on.
 */
class RollsFetchKeepsLiveRollTest {
    private val september = Instant.parse("2024-09-19T08:00:00Z").toEpochMilli()
    private val december = Instant.parse("2024-12-19T08:00:00Z").toEpochMilli()

    @Test
    fun `a live record the stored bars cannot price is kept, and one they can price is measured again`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "futures:\n  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001, " +
                "roll: { daysBeforeExpiry: 8, atUtc: '08:00', adjust: panama } }\n",
        )
        val codes = listOf("BTCUSDT_240927", "BTCUSDT_241227", "BTCUSDT_250328")
        val expiries = listOf("2024-09-27", "2024-12-27", "2025-03-28")
        ContractCatalogStore(dir).write(
            ContractCatalog(
                "BINANCE_UM:BTCUSDT",
                codes.zip(expiries).map { (c, d) -> ListedContract(c, Instant.parse("${d}T08:00:00Z").toEpochMilli()) },
            ),
        )
        val day = LocalDate.parse("2024-09-19")
        val bars = LocalBarStore(dir)
        for ((code, close) in listOf(codes[0] to "63000", codes[1] to "63800")) {
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 28_740_000L
            val px = BigDecimal(close)
            val bar = Candle("BINANCE_UM:$code", px, px, px, px, BigDecimal.ONE, start, start + 60_000)
            bars.writeDay("BINANCE_UM", code, "1m", day, listOf(bar))
            bars.recordDay("BINANCE_UM", code, "1m", day)
        }
        val live = RollRecord(december, codes[1], codes[2], "97000", "98500")
        val stale = RollRecord(september, codes[0], codes[1], "1", "1")
        RollHistoryStore(dir).write(RollHistory("BINANCE_UM:BTCUSDT", "8d@08:00", listOf(stale, live)))

        val code =
            PrintStream(ByteArrayOutputStream()).use { quiet ->
                val original = System.out
                try {
                    System.setOut(quiet)
                    RollsFetch.run("BINANCE_UM:BTCUSDT", dir, null, nowMs = december + 86_400_000L) { _, _ -> }
                } finally {
                    System.setOut(original)
                }
            }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val history = RollHistoryStore(dir).read("BINANCE_UM:BTCUSDT")!!
        assertThat(history.find(december, codes[1], codes[2])).isEqualTo(live)
        assertThat(history.find(september, codes[0], codes[1])?.toPrice).isEqualTo("63800")
    }
}
