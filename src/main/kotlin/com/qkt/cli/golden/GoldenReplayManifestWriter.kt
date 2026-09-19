package com.qkt.cli.golden

import com.qkt.candles.TimeWindow
import com.qkt.cli.BuildInfo
import com.qkt.common.Clock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Writes `golden-replay-manifest.json`: where the replay data came from, the replay window that
 * covers the live ticks, and how many bars each provenance contributed.
 */
internal class GoldenReplayManifestWriter(
    private val bundle: Path,
    private val clock: Clock,
) {
    /** Write the replay manifest for [capture] and its merged [replayCandles] under [root]. */
    fun write(
        root: Path,
        capture: GoldenMarketCapture,
        replayCandles: List<RecordedCandle>,
    ) {
        val liveTicks = capture.ticks.filterNot { it.warmup }
        val firstLiveMs = liveTicks.minOf { it.tick.timestamp }
        val lastLiveMs = liveTicks.maxOf { it.tick.timestamp }
        val containingStarts =
            replayCandles
                .map { it.candle }
                .filter { firstLiveMs >= it.startTime && firstLiveMs < it.endTime }
                .map { it.startTime }
        val fromMs = containingStarts.maxOrNull() ?: firstLiveMs
        val toMs =
            maxOf(
                lastLiveMs + 1L,
                replayCandles.maxOfOrNull { it.candle.endTime } ?: lastLiveMs + 1L,
            )
        val symbols =
            capture.ticks
                .map { it.tick.symbol }
                .toSet()
                .sorted()
        val timeframes =
            replayCandles
                .map { TimeWindow(it.candle.endTime - it.candle.startTime).canonicalSpec() }
                .toSet()
                .sorted()
        val materializedBars =
            replayCandles
                .groupBy {
                    TimeWindow(it.candle.endTime - it.candle.startTime).canonicalSpec() to it.provenance
                }.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        val sourceManifest = capture.manifest
        val sourceSession = requireText(sourceManifest, "session", "manifest.json", 1L)
        val sourceCaptureGitSha = requireText(sourceManifest, "captureGitSha", "manifest.json", 1L)
        val materializedAt = Instant.ofEpochMilli(clock.now()).toString()
        val text =
            buildString {
                append("{\n")
                append("  \"schema\": \"qkt-golden-replay-data-v1\",\n")
                append("  \"schemaVersion\": 1,\n")
                append("  \"sourceBundleSha256\": ").append(jsonString(sha256(bundle))).append(",\n")
                append("  \"sourceSession\": ").append(jsonString(sourceSession)).append(",\n")
                append("  \"sourceCaptureGitSha\": ").append(jsonString(sourceCaptureGitSha)).append(",\n")
                append("  \"materializerGitSha\": ").append(jsonString(BuildInfo.GIT_SHA)).append(",\n")
                append("  \"materializedAtUtc\": ").append(jsonString(materializedAt)).append(",\n")
                append("  \"replayWindow\": {\"fromMs\": ").append(fromMs)
                append(", \"toMs\": ").append(toMs)
                append(", \"fromUtc\": ").append(jsonString(Instant.ofEpochMilli(fromMs).toString()))
                append(", \"toUtc\": ").append(jsonString(Instant.ofEpochMilli(toMs).toString())).append("},\n")
                append("  \"counts\": {\"ticks\": ").append(liveTicks.size)
                append(", \"warmupTicks\": ").append(capture.ticks.size - liveTicks.size)
                append(", \"candles\": ").append(capture.candles.size)
                append(", \"streamCandles\": ").append(capture.streamCandles.size)
                append(", \"strategyCandleEvaluations\": ").append(capture.strategyCandleEvaluations)
                append(", \"materializedCandles\": ").append(replayCandles.size).append("},\n")
                append("  \"symbols\": [").append(symbols.joinToString(",") { jsonString(it) }).append("],\n")
                append("  \"timeframes\": [").append(timeframes.joinToString(",") { jsonString(it) }).append("],\n")
                append("  \"materializedBars\": [\n")
                materializedBars.forEachIndexed { index, (key, records) ->
                    append("    {\"timeframe\": ").append(jsonString(key.first))
                    append(", \"provenance\": ").append(jsonString(key.second))
                    append(", \"count\": ").append(records.size).append('}')
                    if (index != materializedBars.lastIndex) append(',')
                    append('\n')
                }
                append("  ]\n")
                append("}\n")
            }
        val file = root.resolve("golden-replay-manifest.json")
        Files.writeString(file, text, StandardCharsets.UTF_8)
        makePrivateFile(file)
    }
}
