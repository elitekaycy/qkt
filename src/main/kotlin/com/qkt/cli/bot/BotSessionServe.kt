package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionDescriptor
import com.qkt.trade.session.BotSessionFiles
import com.qkt.trade.session.BotSessionServer
import com.qkt.trade.session.SessionMeta
import com.qkt.trade.session.writeSessionMeta
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serves [session] on loopback until a client finishes it: writes the descriptor clients find it by
 * (`<state>/bot/sessions/<run>/session.json`), prints the start line, and cleans up on the way out.
 */
internal fun serveSession(
    sub: Args,
    json: Boolean,
    session: BotRunSession,
    mode: String,
    cfg: com.qkt.cli.Config,
    stateRoot: Path,
    quoteContextFor: ((String) -> com.qkt.trade.BotQuoteContext)?,
    serverThreads: Int,
    onFinish: (com.qkt.backtest.BacktestResult?) -> String?,
    insightsSink: com.qkt.observe.insights.InsightsSink? = null,
): Int {
    val token = randomSessionToken()
    val sessionDir = BotSessionFiles.sessionDir(stateRoot, session.runId)
    Files.createDirectories(sessionDir)
    writeSessionMeta(
        sessionDir,
        SessionMeta(
            session.runId,
            mode,
            session.identities().toList(),
            sub.tokens.toList(),
            System.currentTimeMillis(),
        ),
    )
    val trail =
        com.qkt.trade.BotTrail(
            stateRoot,
            cfg.insights,
            com.qkt.common.SystemClock(),
            run = session.runId,
            sharedSink = insightsSink,
        )
    val server =
        BotSessionServer(
            session = session,
            token = token,
            accountCurrency = cfg.accountCurrency,
            onFinish = onFinish,
            quoteContextFor = quoteContextFor,
            serverThreads = serverThreads,
            readsJournal = sessionDir.resolve("reads.jsonl"),
            trail = trail,
        )
    server.start()
    BotSessionFiles.write(
        stateRoot,
        BotSessionDescriptor(
            runId = session.runId,
            port = server.boundPort,
            token = token,
            mode = mode,
            pid = ProcessHandle.current().pid(),
        ),
    )
    val started =
        jsonObj(
            "ok" to true,
            "run" to session.runId,
            "mode" to mode,
            "port" to server.boundPort,
            "url" to "http://127.0.0.1:${server.boundPort}",
            "descriptor" to BotSessionFiles.sessionDir(stateRoot, session.runId).resolve("session.json").toString(),
            "identities" to session.identities().sorted(),
            "series" to session.streams.describe(),
        )
    if (json) println(started) else println("session ${session.runId} listening on 127.0.0.1:${server.boundPort}")
    System.out.flush()
    try {
        while (!server.finished) Thread.sleep(200L)
    } finally {
        BotSessionFiles.delete(stateRoot, session.runId)
        server.close()
        trail.close()
        insightsSink?.close()
    }
    return ExitCodes.SUCCESS
}
