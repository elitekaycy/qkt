package com.qkt.cli.daemon.routes

import com.qkt.observe.AuditJournalHealth

/**
 * `,"auditJournal":{…}` for a session that keeps an engine audit journal, empty otherwise: what
 * `/health` and `/list` report so `qkt status --deep` and watchdogs see a lossy journal.
 */
internal fun renderAuditJournalFields(health: AuditJournalHealth): String {
    if (!health.enabled) return ""
    return ""","auditJournal":{"written":${health.written},"queued":${health.queued},""" +
        """"droppedMarketData":${health.droppedMarketData},"lostDecisionEvents":${health.lostDecisionEvents},""" +
        """"omittedChainQuotes":${health.omittedChainQuotes}}"""
}
