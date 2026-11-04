package com.qkt.cli

import com.qkt.common.FixedClock
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.Manifest
import com.qkt.marketdata.store.ManifestStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Matrix K01: one backtest of the same share on two venues, each leg on its own venue's ticks. */
class BacktestVenueTickStoreTest : BacktestCommandFixture() {
    private val day = 1_705_276_800_000L

    private fun venueTicks(
        root: Path,
        venue: String,
        price: String,
    ) {
        val dir = root.resolve("ticks/$venue/AAPL")
        Files.createDirectories(dir)
        // Every 30 minutes across the day, so the day reads as complete; prices rise by 0.01 a print.
        val rows = (0..47).joinToString("") { i -> "${day + i * 1_800_000L},AAPL,$price.${10 + i},,,,,\n" }
        Files.writeString(
            dir.resolve("2024-01-15.csv"),
            "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n$rows",
        )
        ManifestStore(root, FixedClock(time = day)).write(
            Manifest(symbol = "$venue:AAPL", ranges = listOf(DayRange("2024-01-15", "2024-01-16"))),
        )
    }

    @Test
    fun `each venue's leg trades on its own ticks of a shared bare symbol`(
        @TempDir tmp: Path,
    ) {
        val data = tmp.resolve("data")
        venueTicks(data, "EXNESS", "270")
        venueTicks(data, "ALPACA", "150")
        val strategy = tmp.resolve("k01.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY k01 VERSION 1

            SYMBOLS
                ex = EXNESS:AAPL EVERY 1m
                al = ALPACA:AAPL EVERY 1m

            RULES
                WHEN ex.close > 200
                THEN BUY ex SIZING 1
                WHEN al.close < 200
                THEN BUY al SIZING 1
            """.trimIndent() + "\n",
        )

        val (code, stdout, stderr) =
            runBacktest(
                "backtest",
                strategy.toString(),
                "--from",
                "2024-01-15",
                "--to",
                "2024-01-16",
                "--data-root",
                data.toString(),
                "--no-fetch",
                "--json",
            )

        assertThat(code).withFailMessage("stderr=$stderr").isEqualTo(ExitCodes.SUCCESS)
        assertThat(stderr).contains("tick coverage EXNESS:AAPL 1/1").contains("tick coverage ALPACA:AAPL 1/1")
        val result = Json.parseToJsonElement(stdout.trim().lines().last()) as JsonObject
        // Read by bare symbol, both legs would see one price series and only one rule could fire.
        assertThat(result.getValue("trades").jsonPrimitive.content).isEqualTo("2")
    }
}
