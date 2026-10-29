package com.qkt.observe.insights

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a strategy's source text to the first `strategy.started` one sink sends for it at one sha256.
 * A later start of the same strategy at the same sha (a portfolio restarting an unchanged child, a
 * redeploy of the same file) keeps `sourceSha256` but leaves `sourceText` and `sourceTruncated` out
 * and says so with `sourceTextOmitted: true`, so the collector, which stores each version once, is not
 * sent the text again. A new process sends it again. Safe on any thread.
 *
 * e.g. `gold` started at sha `9f2c...` twice: the first envelope carries the text, the second
 * `{"sourceSha256": "9f2c...", "sourceTextOmitted": true}`.
 */
class StrategySourceLedger {
    private val sent = ConcurrentHashMap.newKeySet<String>()

    /** [envelope], or for a repeated start of a strategy at a sha already sent, the same without its text. */
    fun admit(envelope: InsightsEnvelope): InsightsEnvelope {
        if (envelope.type != "strategy.started") return envelope
        val payload = envelope.payload
        if (payload["sourceText"] == null) return envelope
        val sha = payload["sourceSha256"] as? String ?: return envelope
        if (sent.add("${payload["strategyId"]}\u0000$sha")) return envelope
        return envelope.copy(payload = payload - "sourceText" - "sourceTruncated" + ("sourceTextOmitted" to true))
    }
}
