package com.qkt.cli.audit

import com.qkt.cli.ExitCodes
import com.qkt.cli.Mt5FeedAudit
import com.qkt.cli.ObservedMt5Tick
import com.qkt.connector.mt5.MT5Client

/**
 * Polls MT5 quotes for [durationSeconds], waits [settleMs] for venue history to settle, then
 * reconciles every polled quote against raw MT5 tick history for the same UTC window.
 */
internal fun runMt5HistoryAudit(
    client: MT5Client,
    brokerSymbol: String,
    qktSymbol: String,
    profileName: String,
    durationSeconds: Long,
    pollMs: Long,
    settleMs: Long,
    jsonOutput: Boolean,
    outPath: String?,
): Int {
    val startedAtMs = System.currentTimeMillis()
    val deadline = startedAtMs + durationSeconds * 1_000L
    val observations = mutableListOf<ObservedMt5Tick>()
    while (System.currentTimeMillis() < deadline) {
        client.getTick(brokerSymbol)?.let { tick ->
            observations += ObservedMt5Tick(System.currentTimeMillis(), tick)
        }
        Thread.sleep(pollMs)
    }
    val endedAtMs = System.currentTimeMillis()
    Thread.sleep(settleMs)
    val history =
        client.getTicksRange(brokerSymbol, startedAtMs, endedAtMs)
            ?: run {
                System.err.println("qkt audit-ticks: MT5 raw tick history is unavailable")
                return ExitCodes.USER_ERROR
            }
    val result =
        runCatching {
            Mt5FeedAudit.compare(qktSymbol, startedAtMs, endedAtMs, observations, history)
        }.getOrElse { error ->
            System.err.println("qkt audit-ticks: ${error.message}")
            return ExitCodes.USER_ERROR
        }
    val json =
        Mt5FeedAudit.artifactJson(
            result = result,
            venueSymbol = brokerSymbol,
            profileName = profileName,
            durationSeconds = durationSeconds,
            pollMs = pollMs,
            settleMs = settleMs,
        )
    if (jsonOutput) {
        println(json)
    } else {
        println("poll samples:             ${result.pollSamples}")
        println("unique in-window ticks:   ${result.uniqueLiveTicks}")
        println("history ticks:            ${result.historyTicks}")
        println("exact timestamp matches:  ${result.exactTimestampMatches}")
        println("exact bid/ask matches:    ${result.exactPriceMatches}")
        println("timestamp price mismatch: ${result.timestampPriceMismatches}")
        println("missing from history:     ${result.missingFromHistory}")
        println("invalid live quotes:      ${result.invalidLiveQuotes}")
        println("quote age p95 ms:         ${result.quoteAgeMs.p95}")
        println("result:                   ${if (result.passed) "PASS" else "FAIL"}")
    }
    persistAuditJson(outPath, json)
    return if (result.passed) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
}
