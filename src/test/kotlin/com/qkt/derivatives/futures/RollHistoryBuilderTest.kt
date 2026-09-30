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

class RollHistoryBuilderTest {
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

    @Test
    fun `reference prices are the last closes at or before the roll instant`() {
        val bars =
            mapOf(
                ("BTCUSDT_240927" to LocalDate.parse("2024-09-19")) to
                    listOf(
                        bar("BTCUSDT_240927", "2024-09-19T07:59:00Z", "63000.1"),
                        bar("BTCUSDT_240927", "2024-09-19T08:00:00Z", "99"),
                    ),
                ("BTCUSDT_241227" to LocalDate.parse("2024-09-19")) to
                    listOf(bar("BTCUSDT_241227", "2024-09-19T07:59:00Z", "63800.2")),
            )
        val history =
            RollHistoryBuilder {
                c,
                d,
                ->
                bars[c to d].orEmpty()
            }.build(root, catalog, setOf(ContinuousSelector.FRONT))
        assertThat(history.policy).isEqualTo("8d@08:00")
        assertThat(history.rolls).hasSize(1)
        with(history.rolls.single()) {
            assertThat(from).isEqualTo("BTCUSDT_240927")
            assertThat(fromPrice).isEqualTo("63000.1")
            assertThat(toPrice).isEqualTo("63800.2")
        }
    }

    @Test
    fun `next rolls are measured from the contracts one further out`() {
        val day = LocalDate.parse("2024-09-19")
        val bars =
            mapOf(
                ("BTCUSDT_240927" to day) to listOf(bar("BTCUSDT_240927", "2024-09-19T07:59:00Z", "63000.1")),
                ("BTCUSDT_241227" to day) to listOf(bar("BTCUSDT_241227", "2024-09-19T07:59:00Z", "63800.2")),
                ("BTCUSDT_250328" to day) to listOf(bar("BTCUSDT_250328", "2024-09-19T07:59:00Z", "64700.3")),
            )
        val history = RollHistoryBuilder { c, d -> bars[c to d].orEmpty() }.build(root, catalog)
        assertThat(
            history.find(ms("2024-09-19T08:00:00Z"), "BTCUSDT_241227", "BTCUSDT_250328")?.toPrice,
        ).isEqualTo("64700.3")
        assertThat(history.rolls).hasSize(2)
    }

    @Test
    fun `leading rolls without data are skipped and the history starts at the first priced roll`() {
        val day = LocalDate.parse("2024-12-19")
        val bars =
            mapOf(
                ("BTCUSDT_241227" to day) to listOf(bar("BTCUSDT_241227", "2024-12-19T07:59:00Z", "97000")),
                ("BTCUSDT_250328" to day) to listOf(bar("BTCUSDT_250328", "2024-12-19T07:59:00Z", "98500")),
            )
        val history =
            RollHistoryBuilder {
                c,
                d,
                ->
                bars[c to d].orEmpty()
            }.build(root, catalog, setOf(ContinuousSelector.FRONT))
        assertThat(history.rolls.map { it.from }).containsExactly("BTCUSDT_241227")
    }
}
