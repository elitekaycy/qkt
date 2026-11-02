package com.qkt.observe.insights

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a strategy's source text, and its effective config, to the first `strategy.started` one sink sends
 * for it at one sha256 of each. A later start of the same strategy at the same sha (a portfolio restarting
 * an unchanged child, a redeploy of the same file) keeps `sourceSha256` but leaves `sourceText` and
 * `sourceTruncated` out and says so with `sourceTextOmitted: true`; likewise `effectiveConfigSha256` and
 * `effectiveConfigOmitted: true`. The collector stores each version once, so it is not sent either again.
 * The two move independently. A new process sends both again. Safe on any thread.
 *
 * e.g. `gold` started at sha `9f2c...` twice: the first envelope carries the text, the second
 * `{"sourceSha256": "9f2c...", "sourceTextOmitted": true}`.
 */
class StrategySourceLedger {
    private val sent = ConcurrentHashMap.newKeySet<String>()

    /** [envelope], or for a repeated start at a sha already sent, the same without what that sha names. */
    fun admit(envelope: InsightsEnvelope): InsightsEnvelope {
        if (envelope.type != "strategy.started") return envelope
        val payload =
            once(
                once(
                    envelope.payload,
                    "sourceSha256",
                    "sourceText",
                    setOf("sourceText", "sourceTruncated"),
                    "sourceTextOmitted",
                ),
                "effectiveConfigSha256",
                "effectiveConfig",
                setOf("effectiveConfig"),
                "effectiveConfigOmitted",
            )
        return if (payload === envelope.payload) envelope else envelope.copy(payload = payload)
    }

    private fun once(
        payload: Map<String, Any?>,
        shaKey: String,
        bodyKey: String,
        dropped: Set<String>,
        omittedKey: String,
    ): Map<String, Any?> {
        if (payload[bodyKey] == null) return payload
        val sha = payload[shaKey] as? String ?: return payload
        if (sent.add("$bodyKey\u0000${payload["strategyId"]}\u0000$sha")) return payload
        return payload - dropped + (omittedKey to true)
    }
}
