package com.qkt.backtest

import com.qkt.cli.Args
import com.qkt.cli.BacktestContext
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.LocalBarStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.copyToRecursively
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A continuous stream held across the real 2024-09-19 08:00 BTCUSDT roll
 * (`src/test/resources/futures/btcusdt-rolls`, see its PROVENANCE.md), with two ticks of slippage
 * and the 0.05% taker fee on every contract fill. The engine books in the adjusted series; the
 * test rebuilds the contract legs from the raw bars and the measured roll, independently of the
 * broker, and requires both to agree to the cent.
 */
class ContinuousFuturesExecutionTest {
    private val sep = "BINANCE_UM:BTCUSDT_240927"
    private val dec = "BINANCE_UM:BTCUSDT_241227"
    private val quantity = BigDecimal("0.01")
    private val taker = BigDecimal("0.0005")

    // Measured rolls (contracts/BINANCE_UM/BTCUSDT.rolls.json): the series is anchored at June, so
    // September sits 65966.9 - 68032.8 below it and December a further 62206.4 - 63343.9.
    private val sepShift = BigDecimal("65966.9").subtract(BigDecimal("68032.8"))
    private val decShift = sepShift.add(BigDecimal("62206.4")).subtract(BigDecimal("63343.9"))

    private fun run(dir: Path): Pair<BacktestResult, Path> {
        val fixture = Paths.get(requireNotNull(javaClass.getResource("/futures/btcusdt-rolls")).toURI())
        val data = dir.resolve("data")
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        fixture.copyToRecursively(data, followLinks = false, overwrite = false)
        val instruments = data.resolve("instruments.yaml")
        Files.writeString(instruments, Files.readString(instruments) + "    slippageTicks: 2\n")
        val strategy = dir.resolve("hold.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY hold VERSION 1
            SYMBOLS
                btc = BINANCE_UM:BTCUSDT@front EVERY 15m
            RULES
                WHEN btc.close > 0
                THEN BUY btc SIZING 0.01 EXIT AFTER 2d
            """.trimIndent(),
        )
        val ast = (Dsl.parseFile(strategy) as ParseResult.Success).value
        val argv =
            arrayOf(
                "backtest",
                strategy.toString(),
                "--from",
                "2024-09-18",
                "--to",
                "2024-09-21",
                "--data-root",
                data.toString(),
                "--no-fetch",
                "--allow-incomplete",
                "--slippage",
                "instrument",
            )
        val ctx = BacktestContext.build(Args(argv), ast)
        ctx.provision()
        return ctx.backtest(emptyMap()).run() to data
    }

    /** Every open, high, low and close [contract] printed on [day]. */
    private fun prints(
        data: Path,
        contract: String,
        day: LocalDate,
    ): Set<BigDecimal> =
        LocalBarStore(data)
            .readDay("BINANCE_UM", contract.substringAfter(':'), "15m", day)
            .flatMap { listOf(it.open, it.high, it.low, it.close) }
            .map { it.stripTrailingZeros() }
            .toSet()

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    @Test
    fun `a position held across the roll books exactly the P&L of the contracts it traded`(
        @TempDir dir: Path,
    ) {
        val (result, data) = run(dir)

        val roll = result.rolls.single()
        assertThat(roll.from).isEqualTo(sep)
        assertThat(roll.to).isEqualTo(dec)
        assertThat(roll.atMs).isEqualTo(Instant.parse("2024-09-19T08:00:00Z").toEpochMilli())
        assertThat(roll.quantity).isEqualByComparingTo(quantity)
        assertThat(roll.fromFill).isEqualByComparingTo("62206.2")
        assertThat(roll.toFill).isEqualByComparingTo("63344.1")

        val fills = result.trades.map { it.trade }
        assertThat(fills).hasSize(2)
        assertThat(fills.map { it.symbol }.toSet()).containsExactly("BINANCE_UM:BTCUSDT@front")
        val (entry, exit) = fills
        assertThat(entry.timestamp).isLessThan(roll.atMs)
        assertThat(exit.timestamp).isGreaterThan(roll.atMs)

        // The contract fills behind the engine's: a market buy slipped two ticks above a September
        // print, a market sell two ticks below a December print.
        val entryOnSep = entry.price.subtract(sepShift)
        val exitOnDec = exit.price.subtract(decShift)
        val tick = BigDecimal("0.1")
        assertThat(
            prints(data, sep, dayOf(entry.timestamp)),
        ).contains(entryOnSep.subtract(tick.multiply(BigDecimal(2))).stripTrailingZeros())
        assertThat(
            prints(data, dec, dayOf(exit.timestamp)),
        ).contains(exitOnDec.add(tick.multiply(BigDecimal(2))).stripTrailingZeros())

        val legs =
            roll.fromFill
                .subtract(entryOnSep)
                .add(exitOnDec.subtract(roll.toFill))
                .multiply(quantity)
        val fees =
            entryOnSep
                .add(roll.fromFill)
                .add(roll.toFill)
                .add(exitOnDec)
                .multiply(quantity)
                .multiply(taker)
        val strategy = result.perStrategy.values.single()
        assertThat(strategy.unrealizedTotal).isEqualByComparingTo("0")
        assertThat(strategy.realizedTotal).isEqualByComparingTo(legs.subtract(fees))
        assertThat(result.global.realizedTotal).isEqualByComparingTo(legs.subtract(fees))
    }

    @Test
    fun `the roll's cost is booked once and the entry and exit fees are reported as commission`(
        @TempDir dir: Path,
    ) {
        val (result, _) = run(dir)

        val roll = result.rolls.single()
        val costs =
            requireNotNull(result.causality).accountedFills.filter {
                it.kind ==
                    com.qkt.events.FillAccountingKind.COST
            }
        assertThat(costs).hasSize(1)
        assertThat(costs.single().netStrategyAccountRealized).isEqualByComparingTo(roll.cost.negate())
        // Two ticks each way on 0.01 is 0.004 of slippage; the roll legs' fees are the rest.
        assertThat(roll.cost).isEqualByComparingTo(roll.fees.add(BigDecimal("0.004")))
        val (entry, exit) = result.trades.map { it.trade }
        val entryFee =
            entry.price
                .subtract(sepShift)
                .multiply(quantity)
                .multiply(taker)
        val exitFee =
            exit.price
                .subtract(decShift)
                .multiply(quantity)
                .multiply(taker)
        assertThat(result.global.commissionPaid).isEqualByComparingTo(entryFee.add(exitFee))
    }
}
