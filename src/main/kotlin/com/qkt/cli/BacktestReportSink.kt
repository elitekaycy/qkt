package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.report.BacktestReportWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Where a `qkt backtest` report bundle lands (#1372): `--report-dir DIR` wins, `--no-report`
 * writes nothing, otherwise a timestamped directory under `<home>/.qkt/runs` (next to the data
 * store) is created automatically and its path printed. [home] defaults to the real home so
 * tests can redirect it.
 */
internal object BacktestReportSink {
    private val stampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun userHome(): Path = Path.of(System.getProperty("user.home"))

    /**
     * Home the runs root resolves under: the `qkt.runs.home` system property when set (the test
     * JVM sets it into the build dir so suites never touch the real home), else the real home.
     * An explicit override (constructor, `--report-dir`) always wins over both.
     */
    fun defaultHome(): Path = System.getProperty("qkt.runs.home")?.let { Path.of(it) } ?: userHome()

    fun resolve(
        args: Args,
        strategyPath: Path,
        home: Path = defaultHome(),
        reportBase: Path? = null,
    ): Path? {
        if (args.flag("no-report")) return null
        args.option("report-dir")?.let { return Path.of(it) }
        val stamp = LocalDateTime.now(ZoneOffset.UTC).format(stampFmt)
        val label =
            strategyPath.fileName
                .toString()
                .removeSuffix(".qkt")
                .replace(Regex("[^A-Za-z0-9_-]+"), "-")
                .trim('-')
                .ifEmpty { "strategy" }
        val base = reportBase ?: home.resolve(".qkt").resolve("runs")
        return base.resolve("$stamp-$label")
    }

    fun write(
        dir: Path,
        result: BacktestResult,
    ) {
        Files.createDirectories(dir)
        BacktestReportWriter(dir).write(result)
    }

    /** Display path with the home prefix shortened to `~`, e.g. `~/.qkt/runs/20251009-120000-first`. */
    fun display(
        dir: Path,
        home: Path = userHome(),
    ): String =
        if (dir.startsWith(home)) {
            "~/" + home.relativize(dir).toString()
        } else {
            dir.toString()
        }
}
