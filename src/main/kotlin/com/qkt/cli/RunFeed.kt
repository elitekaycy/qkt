package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * The fallback feed `qkt run` paper-trades on. With a `qkt.config.yaml` at [configPath] it is that
 * file's `source` ([configured]); without one it is TradingView (`tv`), as `qkt run --help` promises.
 * The built-in config defaults say `local` (no fallback feed), which left a first `qkt run` with no
 * prices at all.
 */
internal fun runFeed(
    configPath: Path,
    configured: String,
): String = if (Files.exists(configPath)) configured else "tv"
