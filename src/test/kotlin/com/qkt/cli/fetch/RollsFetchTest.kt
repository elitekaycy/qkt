package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.instrument.RollHistoryStore
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

class RollsFetchTest {
    private fun contract(
        symbol: String,
        expiry: String,
    ) = ListedContract(symbol, Instant.parse(expiry).toEpochMilli())

    private fun setUp(
        dir: Path,
        contracts: List<ListedContract>,
    ) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "futures:\n  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, " +
                "volumeMin: 0.001, roll: { daysBeforeExpiry: 8, atUtc: '08:00', adjust: panama } }\n",
        )
        ContractCatalogStore(dir).write(ContractCatalog("BINANCE_UM:BTCUSDT", contracts))
    }

    /** Runs the fetch, writing one bar at 08:00 per requested day except for [unlisted] contracts. */
    private fun fetch(
        dir: Path,
        fetched: MutableList<Pair<String, LocalDate>> = mutableListOf(),
        unlisted: Set<String> = emptySet(),
    ): Pair<Int, String> {
        val store = LocalBarStore(dir)
        val out = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                RollsFetch.run("BINANCE_UM:BTCUSDT", dir, instruments = null) { contract, day ->
                    fetched += contract to day
                    if (contract in unlisted) return@run
                    val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    val px = BigDecimal(if (contract.endsWith("0927")) "63000" else "63800")
                    val bar =
                        Candle(
                            "BINANCE_UM:$contract",
                            px,
                            px,
                            px,
                            px,
                            BigDecimal.ONE,
                            start + 28_740_000L,
                            start + 28_800_000L,
                        )
                    store.writeDay("BINANCE_UM", contract, "1m", day, listOf(bar))
                    store.recordDay("BINANCE_UM", contract, "1m", day)
                }
            } finally {
                System.setOut(original)
            }
        return code to out.toString()
    }

    @Test
    fun `missing roll days are fetched and the history is written`(
        @TempDir dir: Path,
    ) {
        setUp(
            dir,
            listOf(
                contract("BTCUSDT_240927", "2024-09-27T08:00:00Z"),
                contract("BTCUSDT_241227", "2024-12-27T08:00:00Z"),
            ),
        )
        val fetched = mutableListOf<Pair<String, LocalDate>>()
        val (code, _) = fetch(dir, fetched)
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(fetched.map { it.first }.toSet()).containsExactlyInAnyOrder("BTCUSDT_240927", "BTCUSDT_241227")
        val history = RollHistoryStore(dir).read("BINANCE_UM:BTCUSDT")
        assertThat(history?.rolls?.single()?.toPrice).isEqualTo("63800")
    }

    @Test
    fun `the report names the unmeasurable roll the history starts after`(
        @TempDir dir: Path,
    ) {
        setUp(
            dir,
            listOf(
                contract("BTCUSDT_240628", "2024-06-28T08:00:00Z"),
                contract("BTCUSDT_240927", "2024-09-27T08:00:00Z"),
                contract("BTCUSDT_241227", "2024-12-27T08:00:00Z"),
            ),
        )
        val (code, output) = fetch(dir, unlisted = setOf("BTCUSDT_240628"))
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(output).contains("measured 1 of 2 front rolls")
        assertThat(output).contains("starts after the 2024-06-20T08:00:00Z roll (BTCUSDT_240628 -> BTCUSDT_240927)")
    }
}
