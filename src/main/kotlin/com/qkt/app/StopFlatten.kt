package com.qkt.app

import org.slf4j.LoggerFactory

/**
 * `stop --flatten`, confirmed rather than assumed: the flatten is queued for the engine thread, then the
 * session's [book] and, where the venue names its positions, the venue ([venueHeld]) are read until
 * the strategy holds nothing: by the venue's list where it names positions, else by the book. A close
 * that never reached the venue (a gateway answering 503 for a minute rejects the submit once it can say
 * it never placed it) leaves a leg open with no close working: the flatten is queued again. A close still working is waited for and never doubled, and a
 * ticket only the venue still lists (its list lags the fill) is waited for, never closed again from an
 * empty book. Bounded by [timeoutMs]: what is still open then is reported, never claimed flat, and an
 * unreadable venue is never taken for a flat one.
 */
internal class StopFlatten(
    private val requestFlatten: () -> Unit,
    private val flattenRunning: () -> Boolean,
    private val book: () -> Book,
    private val venueHeld: () -> List<String>,
    private val venueNamesPositions: Boolean,
    private val nowMs: () -> Long,
    private val sleep: (Long) -> Unit,
    private val timeoutMs: Long,
    private val pollMs: Long = POLL_MS,
) {
    /** The session's own view: the legs it still holds, and whether a close of one is still working. */
    data class Book(
        val openLegs: List<String>,
        val closing: Boolean,
    )

    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    fun run(): FlattenResult {
        val deadline = nowMs() + timeoutMs
        requestFlatten()
        var sent = 1
        var open: List<String> = emptyList()
        var why = "not yet read"
        while (nowMs() < deadline) {
            sleep(pollMs)
            if (flattenRunning()) continue
            val held = runCatching(book).getOrElse { e -> null.also { why = "engine book unreadable: ${e.message}" } }
            val venue = runCatching(venueHeld).getOrElse { e -> null.also { why = "venue unreadable: ${e.message}" } }
            if (held == null || venue == null) continue
            open = (held.openLegs + venue).distinct()
            // A venue that names its positions is the truth (the book catches up with the fills it is sent);
            // one that names none is judged by the book, every close of which it has booked.
            if (if (venueNamesPositions) venue.isEmpty() else held.openLegs.isEmpty()) {
                return FlattenResult(verifiedFlat = true)
            }
            why = if (held.openLegs.isEmpty()) "the venue still lists $venue" else "still open: $open"
            if (held.openLegs.isNotEmpty() && !held.closing) {
                log.warn("stop flatten: {} with no close working; flattening again ({})", held.openLegs, ++sent)
                requestFlatten()
            }
        }
        val detail = "not flat after ${timeoutMs}ms and $sent flatten(s): $why; verify the venue"
        log.error("stop flatten: {}", detail)
        return FlattenResult(verifiedFlat = false, remainingTickets = open, detail = detail)
    }

    private companion object {
        const val POLL_MS: Long = 500L
    }
}
