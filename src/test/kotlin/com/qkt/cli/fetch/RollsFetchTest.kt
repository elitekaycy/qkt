package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.instrument.RollHistoryStore
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RollsFetchTest {
    @Test
    fun `missing roll days are fetched and the history is written`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "futures:\n  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, " +
                "volumeMin: 0.001, roll: { daysBeforeExpiry: 8, atUtc: '08:00', adjust: panama } }\n",
        )
        ContractCatalogStore(dir).write(
            ContractCatalog(
                "BINANCE_UM:BTCUSDT",
                listOf(
                    ListedContract("BTCUSDT_240927", Instant.parse("2024-09-27T08:00:00Z").toEpochMilli()),
                    ListedContract("BTCUSDT_241227", Instant.parse("2024-12-27T08:00:00Z").toEpochMilli()),
                ),
            ),
        )
        val fetched = mutableListOf<Pair<String, LocalDate>>()
        val store = LocalBarStore(dir)
        val code =
            RollsFetch.run("BINANCE_UM:BTCUSDT", dir, instruments = null) { contract, day ->
                fetched += contract to day
                val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                val px = BigDecimal(if (contract.endsWith("0927")) "63000" else "63800")
                store.writeDay(
                    "BINANCE_UM",
                    contract,
                    "1m",
                    day,
                    listOf(
                        Candle(
                            "BINANCE_UM:$contract",
                            px,
                            px,
                            px,
                            px,
                            BigDecimal.ONE,
                            start + 28_740_000L,
                            start + 28_800_000L,
                        ),
                    ),
                )
                store.recordDay("BINANCE_UM", contract, "1m", day)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(fetched.map { it.first }.toSet()).containsExactlyInAnyOrder("BTCUSDT_240927", "BTCUSDT_241227")
        val history = RollHistoryStore(dir).read("BINANCE_UM:BTCUSDT")
        assertThat(history?.rolls?.single()?.toPrice).isEqualTo("63800")
    }
}
