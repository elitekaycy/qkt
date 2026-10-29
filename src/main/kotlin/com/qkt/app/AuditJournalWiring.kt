package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.observe.AuditChainScope
import com.qkt.observe.EngineAuditJournal
import com.qkt.positions.PositionProvider

/**
 * Journal every bus event, first, into [journal], except the quotes of option-chain contracts the
 * session never traded ([AuditChainScope] over the session's [streams]); a contract [positions] holds
 * counts as traded, so a position restored at restart keeps its quotes journaled.
 */
internal fun wireAuditJournal(
    bus: EventBus,
    journal: EngineAuditJournal,
    streams: Collection<String>,
    positions: PositionProvider,
) {
    val scope = AuditChainScope(streams) { symbol -> (positions.positionFor(symbol)?.quantity?.signum() ?: 0) != 0 }
    bus.subscribeAllFirst { e -> if (scope.journals(e)) journal.append(e) else journal.omitted() }
}
