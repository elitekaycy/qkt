package com.qkt.app

import com.qkt.broker.StreamStop
import org.slf4j.LoggerFactory

/**
 * `stop --flatten`, confirmed rather than assumed: the flatten is queued for the engine thread, then the
 * session's [book] and, where the venue names its positions, the venue ([venueHeld]) are read until
 * the strategy holds nothing: by the venue's list where it names positions, else by the book. A close
 * that never reached the venue (a gateway answering 503 for a minute rejects the submit once it can say
 * it never placed it) leaves a leg open with no close working: the flatten is queued again, after 1 s, 2 s, 4 s and so
 * on up to [MAX_RETRY_GAP_MS] apart. A close still working is waited for and never doubled, and a ticket only the venue
 * still lists (its list lags the fill) is waited for, never closed again from an empty book. Bounded by [timeoutMs]:
 * what is still open then is reported, never claimed flat, and an unreadable venue is never taken for a flat one.
 *
 * What a failed roll left on a stopped stream ([streamStops]) cannot be flattened: the stream refuses every order, and
 * will until the holding is gone. Once nothing else is open or closing, that is the answer at once, with what is held,
 * why, and how to close it ([STRANDED_HOW]); nothing is sent again. Such a holding is never flat, even with the
 * session's book empty (the rest of an unwind that failed is held by the lane alone).
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
    private val streamStops: () -> List<StreamStop> = { emptyList() },
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
        var retryGap = FIRST_RETRY_GAP_MS
        var nextRetry = nowMs() + retryGap
        var open: List<String> = emptyList()
        var why = "not yet read"
        while (nowMs() < deadline) {
            sleep(pollMs)
            if (flattenRunning()) continue
            val held = runCatching(book).getOrElse { e -> null.also { why = "engine book unreadable: ${e.message}" } }
            val venue = runCatching(venueHeld).getOrElse { e -> null.also { why = "venue unreadable: ${e.message}" } }
            if (held == null || venue == null) continue
            val stops = streamStops()
            stranded(held, stops)?.let { return it }
            open = (held.openLegs + venue).distinct()
            // A venue that names its positions is the truth (the book catches up with the fills it is sent);
            // one that names none is judged by the book, every close of which it has booked.
            val flat = if (venueNamesPositions) venue.isEmpty() else held.openLegs.isEmpty()
            if (flat && stops.none { it.holds.isNotEmpty() }) return FlattenResult(verifiedFlat = true)
            why = if (held.openLegs.isEmpty()) "the venue still lists $venue" else "still open: $open"
            if (held.openLegs.isNotEmpty() && !held.closing && nowMs() >= nextRetry) {
                log.warn("stop flatten: {} with no close working; flattening again ({})", held.openLegs, ++sent)
                requestFlatten()
                retryGap = minOf(retryGap * 2, MAX_RETRY_GAP_MS)
                nextRetry = nowMs() + retryGap
            }
        }
        val detail = "not flat after ${timeoutMs}ms and $sent flatten(s): $why; verify the venue"
        log.error("stop flatten: {}", detail)
        return FlattenResult(verifiedFlat = false, remainingTickets = open, detail = detail)
    }

    /** The answer when all that is left sits on stopped streams and no close is working; null otherwise. */
    private fun stranded(
        held: Book,
        stops: List<StreamStop>,
    ): FlattenResult? {
        if (held.closing) return null
        val onStopped = held.openLegs.filter { leg -> stops.any { leg.endsWith("(${it.stream})") } }
        val holdings = stops.flatMap { it.holds }
        if (onStopped.size != held.openLegs.size || (onStopped.isEmpty() && holdings.isEmpty())) return null
        val what = holdings.ifEmpty { onStopped }
        val reasons = stops.filter { it.holds.isNotEmpty() || onStopped.isNotEmpty() }.joinToString("; ") { it.reason }
        val detail = "still holds ${what.joinToString()}: $reasons. $STRANDED_HOW"
        log.error("stop flatten: {}", detail)
        return FlattenResult(verifiedFlat = false, remainingTickets = what, detail = detail)
    }

    private companion object {
        const val POLL_MS: Long = 500L
        const val FIRST_RETRY_GAP_MS: Long = 1_000L
        const val MAX_RETRY_GAP_MS: Long = 30_000L
        const val STRANDED_HOW: String =
            "qkt cannot close this: a stopped stream refuses every order, and qkt resume is refused while it is " +
                "held. " +
                "It closes when the contract settles, heard by a running session or, for a contract that expired " +
                "while away, by the next start; qkt resume then clears the stop. To be out sooner, close it on the " +
                "venue itself: qkt does not hear of that close, and books the holding closed at the settlement price."
    }
}
