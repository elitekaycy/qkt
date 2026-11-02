package com.qkt.cli.daemon.portfolio

import com.qkt.app.LiveSessionHandle
import com.qkt.risk.HaltScope

/** A portfolio child's live session as the book's risk aggregator acts on it. */
internal class SessionRiskTarget(
    private val live: LiveSessionHandle,
) : ChildRiskTarget {
    override fun flatten() = live.flatten()

    override fun halt(
        reason: String,
        scope: HaltScope,
    ) = live.halt(reason, scope)

    override fun resume() = live.resume()

    override fun isHalted(): Boolean = live.isHalted()

    override fun haltReason(): String? = live.haltReason()

    override fun haltScope(): HaltScope? = live.haltScope()
}
