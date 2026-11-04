package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollPolicy
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A history built before a roll happens holds no record of it (#1615): the roll is priced once its minute is over. */
class RollHistoryAsOfTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val catalog =
        ContractCatalog(
            root.root,
            listOf(
                ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                ListedContract("BTCUSDT_250328", ms("2025-03-28T08:00:00Z")),
            ),
        )

    private fun bar(
        contract: String,
        startIso: String,
        close: String,
    ) = Candle(
        "BINANCE_UM:$contract",
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal.ONE,
        ms(startIso),
        ms(startIso) + 60_000L,
    )

    /** Bars of both rolls; December's are the last ones before it, as a fetch minutes ahead of it sees them. */
    private val bars =
        mapOf(
            ("BTCUSDT_240927" to LocalDate.parse("2024-09-19")) to
                listOf(bar("BTCUSDT_240927", "2024-09-19T07:59:00Z", "63000")),
            ("BTCUSDT_241227" to LocalDate.parse("2024-09-19")) to
                listOf(bar("BTCUSDT_241227", "2024-09-19T07:59:00Z", "63800")),
            ("BTCUSDT_241227" to LocalDate.parse("2024-12-19")) to
                listOf(bar("BTCUSDT_241227", "2024-12-19T07:50:00Z", "97000")),
            ("BTCUSDT_250328" to LocalDate.parse("2024-12-19")) to
                listOf(bar("BTCUSDT_250328", "2024-12-19T07:50:00Z", "98500")),
        )

    private fun builtAt(iso: String) =
        RollHistoryBuilder(asOfMs = ms(iso)) { c, d -> bars[c to d].orEmpty() }
            .build(root, catalog, setOf(ContinuousSelector.FRONT))

    @Test
    fun `a roll still ahead is not recorded from the bars before it`() {
        val history = builtAt("2024-12-19T07:55:00Z")

        assertThat(history.rolls.map { it.atMs }).containsExactly(ms("2024-09-19T08:00:00Z"))
    }

    @Test
    fun `a roll inside its own minute is not recorded yet`() {
        val history = builtAt("2024-12-19T08:00:30Z")

        assertThat(history.rolls.map { it.atMs }).containsExactly(ms("2024-09-19T08:00:00Z"))
    }

    @Test
    fun `a roll whose minute has passed is recorded`() {
        val history = builtAt("2024-12-19T08:01:00Z")

        assertThat(history.rolls.map { it.atMs })
            .containsExactly(ms("2024-09-19T08:00:00Z"), ms("2024-12-19T08:00:00Z"))
    }
}
