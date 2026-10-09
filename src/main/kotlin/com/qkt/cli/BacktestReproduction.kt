package com.qkt.cli

import com.qkt.backtest.report.ReproductionInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * Captures what produced a backtest run for the report's "Reproduce this run" section: the exact
 * command line plus the strategy and config sources. Best-effort reads — an unreadable file
 * degrades to a note, never a failed run.
 */
internal fun reproductionInfo(
    args: Args,
    path: Path,
): ReproductionInfo {
    val commandLine = "qkt " + args.tokens.joinToString(" ") { it.shellQuote() }
    val strategySource =
        runCatching { Files.readString(path) }.getOrElse { "Unreadable strategy file: ${it.message}" }
    val configPath = Config.resolvePath(args.option("config"))
    val configSource =
        if (Files.exists(configPath)) {
            runCatching { Files.readString(configPath) }.getOrElse { "Unreadable config file: ${it.message}" }
        } else {
            null
        }
    return ReproductionInfo(
        commandLine = commandLine,
        strategyFile = path.toString(),
        strategySource = strategySource,
        configFile = configPath.toString().takeIf { configSource != null },
        configSource = configSource,
        qktVersion = BuildInfo.VERSION,
        gitSha = BuildInfo.GIT_SHA,
    )
}

private fun String.shellQuote(): String = if (any { it.isWhitespace() }) "'$this'" else this
