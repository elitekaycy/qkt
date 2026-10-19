package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.trade.session.BotSessionStreams
import java.security.SecureRandom

/**
 * `qkt bot session start|status|finish` — lifecycle of a bot run session.
 *
 * `start --backtest` assembles the same store-backed replay `qkt backtest` uses (data provisioning,
 * config-driven halt rules, instruments) with one compiled DSL strategy per identity in the strategy
 * slots, then serves the session over local HTTP until `finish` writes the standard report artifacts
 * to `--out`. Without `--backtest` it trades live through the configured accounts. The command runs in
 * the foreground; run it under `&`, tmux, or a container for a long session.
 */
class BotSessionCommand(
    private val args: Args,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) {
            when (val sub = args.firstNonOption()) {
                "start" -> start(Args(args.tokens.drop(1).toTypedArray()), json)
                "status" -> status()
                "finish" -> finish()
                else -> error("usage: qkt bot session start|status|finish (got '${sub ?: ""}')")
            }
        }
    }

    private fun start(
        sub: Args,
        json: Boolean,
    ): Int =
        if (sub.flag("backtest")) {
            startBacktestSession(
                sub,
                json,
                parseSessionStart(sub) {
                    "run-${sub.requireOption("from")}-${sub.requireOption("to")}"
                },
            )
        } else {
            startLiveSession(sub, json, parseSessionStart(sub) { "live-${com.qkt.common.SystemClock().now()}" })
        }

    private fun status(): Int {
        val client = BotSessionClient.resolve(args) ?: error("no bot session running")
        println(client.get("/status"))
        return ExitCodes.SUCCESS
    }

    private fun finish(): Int {
        val client = BotSessionClient.resolve(args) ?: error("no bot session running")
        val body = if (args.flag("flatten")) """{"flatten":true}""" else "{}"
        println(client.post("/finish", body))
        return ExitCodes.SUCCESS
    }
}

/** What `session start` declares: the series, the run id, the identities, and the warm history depth. */
internal data class SessionStart(
    val streams: BotSessionStreams,
    val runId: String,
    val identities: List<String>,
    val historyBars: Int,
)

/** Reads `--symbols A,B --tf 5m[,1h] --identities a,b --run <id> --history-bars N`. */
internal fun parseSessionStart(
    sub: Args,
    defaultRun: () -> String,
): SessionStart {
    fun list(name: String): List<String> =
        sub
            .option(name)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    val symbols = list("symbols").ifEmpty { error("missing --symbols BROKER:SYMBOL[,..]") }
    val tfs = list("tf").ifEmpty { error("missing --tf (e.g. --tf 5m, or --tf 5m,1h)") }
    return SessionStart(
        streams = BotSessionStreams(symbols, tfs),
        runId = sub.option("run") ?: defaultRun(),
        identities = list("identities").ifEmpty { listOf("manual") },
        historyBars = sub.option("history-bars")?.toIntOrNull() ?: 200,
    )
}

internal fun randomSessionToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
