package com.qkt.cli.bot

import com.qkt.cli.Config
import com.qkt.trade.session.BotSessionStreams
import com.qkt.trade.session.VenueDealLog
import java.nio.file.Path

/**
 * The venue's own record of its executions over the session (`venue-deals.jsonl`), read once at finish for a
 * trace replay. Read-only and best-effort: a venue without deal history leaves the record without it.
 */
internal fun captureVenueDeals(
    cfg: Config,
    streams: BotSessionStreams,
    sessionDir: Path,
    fromMs: Long,
) {
    val now =
        com.qkt.common
            .SystemClock()
            .now()
    val deals =
        streams.symbols.flatMap { sym ->
            runCatching {
                com.qkt.trade.venue.BotVenues.forSymbol(cfg, sym).use { v ->
                    val name = v.venue.venueSymbol(sym)
                    v.venue
                        .deals(fromMs, now)
                        .orEmpty()
                        .filter { it.symbol == name }
                }
            }.getOrElse { e ->
                System.err.println("qkt bot: venue deals not read for $sym: ${e.message}")
                emptyList()
            }
        }
    VenueDealLog.write(sessionDir.resolve(VenueDealLog.FILE), deals.distinctBy { it.dealTicket })
}

/** The venue's spec for each symbol (`instruments.yaml`), so the record replays anywhere. Best-effort. */
internal fun captureVenueInstruments(
    cfg: Config,
    streams: BotSessionStreams,
    sessionDir: Path,
) {
    val specs =
        streams.symbols.mapNotNull { sym ->
            runCatching {
                com.qkt.trade.venue.BotVenues.forSymbol(cfg, sym).use { v ->
                    val f = v.venue.facts(sym)
                    val spec =
                        com.qkt.trade.session.SessionInstruments.Spec(
                            f.contractSize ?: return@use null,
                            f.volumeStep ?: return@use null,
                            f.volumeMin ?: return@use null,
                            f.volumeMax,
                            f.digits ?: return@use null,
                        )
                    sym to spec
                }
            }.getOrElse { e ->
                System.err.println("qkt bot: venue spec not read for $sym: ${e.message}")
                null
            }
        }
    com.qkt.trade.session.SessionInstruments.write(
        sessionDir.resolve(com.qkt.trade.session.SessionInstruments.FILE),
        specs.toMap(),
    )
}
