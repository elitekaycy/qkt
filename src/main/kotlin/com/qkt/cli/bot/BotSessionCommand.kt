package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.trade.session.BotSessionStreams
import java.security.SecureRandom

/**
 * `qkt bot session start|wait|status|finish` — lifecycle of a bot run session.
 *
 * `start --backtest` assembles the same store-backed replay `qkt backtest` uses (data provisioning,
 * config-driven halt rules, instruments) with one compiled DSL strategy per identity in the strategy
 * slots, then serves the session over local HTTP until `finish` writes the standard report artifacts
 * to `--out`. Without `--backtest` it trades live through the configured accounts. The command runs in
 * the foreground; run it under `&`, tmux, or a container for a long session, and `wait --run <id>` before the next
 * verb: a verb that finds no session acts one-shot on the configured venue.
 */
class BotSessionCommand(
    private val args: Args,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) {
            when (val sub = args.firstNonOption()) {
                "start" -> start(Args(args.tokens.drop(1).toTypedArray()), json)
                "wait" -> wait()
                "status" -> status()
                "finish" -> finish()
                "replay" -> return BotReplayCommand(Args(args.tokens.drop(1).toTypedArray())).run()
                else -> error("usage: qkt bot session start|wait|status|finish|replay (got '${sub ?: ""}')")
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

    /** Blocks until the session `--run` names answers (`--timeout` seconds, default 60), then prints its status. */
    private fun wait(): Int {
        val run = args.option("run") ?: error("wait needs --run <id>: the session it waits for")
        val seconds = args.option("timeout")?.removeSuffix("s")?.toLongOrNull() ?: DEFAULT_WAIT_SECONDS
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val status = runCatching { BotSessionClient.resolve(args)?.get("/status") }.getOrNull()
            if (status != null) {
                println(status)
                return ExitCodes.SUCCESS
            }
            Thread.sleep(WAIT_POLL_MS)
        }
        error("bot session $run did not answer within ${seconds}s: is `qkt bot session start ... --run $run` running?")
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
    val identities = list("identities").ifEmpty { listOf("manual") }
    require(
        identities.none { it.startsWith("__") },
    ) { "identities starting with __ are reserved for the session itself" }
    return SessionStart(
        streams = BotSessionStreams(symbols, tfs),
        runId = sub.option("run") ?: defaultRun(),
        identities = identities,
        historyBars = sub.option("history-bars")?.toIntOrNull() ?: 200,
    )
}

private const val DEFAULT_WAIT_SECONDS = 60L
private const val WAIT_POLL_MS = 250L

internal fun randomSessionToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
