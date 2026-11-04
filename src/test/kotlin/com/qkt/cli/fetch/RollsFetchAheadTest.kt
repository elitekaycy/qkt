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

/** `qkt fetch --rolls` minutes ahead of a roll writes no record of it and fetches nothing for it (#1615). */
class RollsFetchAheadTest {
    private val septemberRoll = Instant.parse("2024-09-19T08:00:00Z").toEpochMilli()
    private val decemberRoll = Instant.parse("2024-12-19T08:00:00Z").toEpochMilli()

    private fun setUp(dir: Path) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "futures:\n  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001, " +
                "roll: { daysBeforeExpiry: 8, atUtc: '08:00', adjust: panama } }\n",
        )
        val contracts =
            listOf("BTCUSDT_240927" to "2024-09-27", "BTCUSDT_241227" to "2024-12-27", "BTCUSDT_250328" to "2025-03-28")
                .map { (c, d) -> ListedContract(c, Instant.parse("${d}T08:00:00Z").toEpochMilli()) }
        ContractCatalogStore(dir).write(ContractCatalog("BINANCE_UM:BTCUSDT", contracts))
    }

    /** Fetches as of [nowMs], writing one bar at 07:50 of every requested day. */
    private fun fetch(
        dir: Path,
        nowMs: Long,
        fetched: MutableList<Pair<String, LocalDate>>,
    ): Pair<Int, String> {
        val store = LocalBarStore(dir)
        val out = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                RollsFetch.run("BINANCE_UM:BTCUSDT", dir, instruments = null, nowMs = nowMs) { contract, day ->
                    fetched += contract to day
                    val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 28_200_000L
                    val px = BigDecimal("90000")
                    val bar = Candle("BINANCE_UM:$contract", px, px, px, px, BigDecimal.ONE, start, start + 60_000L)
                    store.writeDay("BINANCE_UM", contract, "1m", day, listOf(bar))
                    store.recordDay("BINANCE_UM", contract, "1m", day)
                }
            } finally {
                System.setOut(original)
            }
        return code to out.toString()
    }

    @Test
    fun `a fetch five minutes before a roll records only the rolls that happened`(
        @TempDir dir: Path,
    ) {
        setUp(dir)
        val fetched = mutableListOf<Pair<String, LocalDate>>()

        val (code, output) = fetch(dir, decemberRoll - 300_000L, fetched)

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val history = RollHistoryStore(dir).read("BINANCE_UM:BTCUSDT")!!
        assertThat(history.rolls.map { it.atMs }.distinct()).containsExactly(septemberRoll)
        assertThat(fetched.map { it.second }.distinct()).allMatch { it.isBefore(LocalDate.parse("2024-12-18")) }
        assertThat(output).contains("measured 1 of 1 front rolls").contains("1 roll(s) still ahead")
    }

    @Test
    fun `a fetch after the roll's minute records it`(
        @TempDir dir: Path,
    ) {
        setUp(dir)

        fetch(dir, decemberRoll + 60_000L, mutableListOf())

        val history = RollHistoryStore(dir).read("BINANCE_UM:BTCUSDT")!!
        assertThat(history.rolls.map { it.atMs }.distinct()).containsExactly(septemberRoll, decemberRoll)
    }
}
