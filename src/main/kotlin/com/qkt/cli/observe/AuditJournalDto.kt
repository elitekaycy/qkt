package com.qkt.cli.observe

import com.qkt.observe.AuditJournalHealth
import kotlinx.serialization.Serializable

/**
 * The engine audit journal's health in an operator status snapshot: records written and waiting,
 * market data dropped under load, decision events lost to a failed write (must stay zero), and
 * option-chain quotes not journaled by design. See [AuditJournalHealth].
 */
@Serializable
data class AuditJournalDto(
    val enabled: Boolean = false,
    val written: Long = 0L,
    val queued: Int = 0,
    val droppedMarketData: Long = 0L,
    val lostDecisionEvents: Long = 0L,
    val omittedChainQuotes: Long = 0L,
) {
    companion object {
        /** The DTO of [health]. */
        fun of(health: AuditJournalHealth): AuditJournalDto =
            AuditJournalDto(
                enabled = health.enabled,
                written = health.written,
                queued = health.queued,
                droppedMarketData = health.droppedMarketData,
                lostDecisionEvents = health.lostDecisionEvents,
                omittedChainQuotes = health.omittedChainQuotes,
            )
    }
}
