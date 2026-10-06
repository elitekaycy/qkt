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

/** Backtests a strategy over one of the real derivative fixtures under `src/test/resources/<resources>`. */
internal object FuturesFixtureRun {
    /**
     * Copies [fixture] into [dir], appends [rootLines] to its root in `instruments.yaml`, and runs
     * [strategy] from [from] to [to] through the CLI's own backtest assembly with [flags].
     */
    fun run(
        dir: Path,
        fixture: String,
        strategy: String,
        from: String,
        to: String,
        rootLines: String = "",
        flags: List<String> = emptyList(),
        resources: String = "futures",
    ): Pair<BacktestResult, Path> {
        val source = Paths.get(requireNotNull(javaClass.getResource("/$resources/$fixture")).toURI())
        val data = dir.resolve("data")
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        source.copyToRecursively(data, followLinks = false, overwrite = false)
        val instruments = data.resolve("instruments.yaml")
        Files.writeString(instruments, Files.readString(instruments) + rootLines)
        val file = dir.resolve("strategy.qkt").also { Files.writeString(it, strategy.trimIndent()) }
        val ast = (Dsl.parseFile(file) as ParseResult.Success).value
        val argv =
            listOf("backtest", file.toString(), "--from", from, "--to", to, "--data-root", data.toString()) +
                listOf("--no-fetch", "--allow-incomplete") + flags
        val ctx = BacktestContext.build(Args(argv.toTypedArray()), ast)
        ctx.provision()
        return ctx.backtest(emptyMap()).run() to data
    }

    /** Every open, high, low and close [contract] printed on the UTC day of [atMs], at [timeframe]. */
    fun prints(
        data: Path,
        contract: String,
        timeframe: String,
        atMs: Long,
    ): Set<BigDecimal> {
        val day: LocalDate = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate()
        return LocalBarStore(data)
            .readDay("BINANCE_UM", contract.substringAfter(':'), timeframe, day)
            .flatMap { listOf(it.open, it.high, it.low, it.close) }
            .map { it.stripTrailingZeros() }
            .toSet()
    }
}
