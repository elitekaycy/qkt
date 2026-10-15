package com.qkt.marketdata.ingest.clock

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.InputLine
import java.time.LocalDateTime

/**
 * Turns one file's wall-clock timestamps, in input order, into UTC epoch milliseconds on [clock].
 *
 * A wall-clock time inside a DST gap (the clock jumped past it) names no instant and refuses. One
 * inside an overlap (the clock repeated an hour) names two; the stream's own order decides which:
 * the earlier offset until the wall clock steps backwards inside the overlap, the later one after.
 * A stream that steps backwards again inside the same overlap cannot be resolved and refuses.
 */
class LocalTimeResolver(
    private val clock: ImportClock,
) {
    private var last: LocalDateTime? = null
    private var laterInOverlap = false

    /** True once any timestamp fell inside a DST overlap (a newest-first file must not, see [ImportClock]). */
    var sawOverlap: Boolean = false
        private set

    /** Epoch milliseconds of [local] read on the clock, [where] naming the line for a refusal. */
    fun toEpochMs(
        local: LocalDateTime,
        where: InputLine,
    ): Long {
        val wall = local.minusHours(clock.shiftHours)
        val offsets = clock.rules.getValidOffsets(wall)
        val previous = last
        last = wall
        if (offsets.size == 1) {
            laterInOverlap = false
            return wall.toInstant(offsets[0]).toEpochMilli()
        }
        val transition =
            clock.rules.getTransition(wall)
                ?: error("no DST transition for $wall on ${clock.spec} although it has ${offsets.size} offsets")
        if (offsets.isEmpty()) {
            throw ImportRefusal(
                "$local does not exist on clock ${clock.spec}: it falls in the DST gap " +
                    "${transition.dateTimeBefore} → ${transition.dateTimeAfter}; the source's clock is not ${clock.spec}",
                listOf(where.toString()),
            )
        }
        sawOverlap = true
        if (previous == null) {
            throw ImportRefusal(
                "the file starts at $local, inside the repeated DST hour of ${clock.spec}: " +
                    "nothing before it says which of the two passes it is",
                listOf(where.toString()),
            )
        }
        if (wall < previous) {
            if (laterInOverlap) {
                throw ImportRefusal(
                    "timestamps step backwards twice inside the repeated DST hour of ${clock.spec} " +
                        "(${transition.dateTimeAfter} → ${transition.dateTimeBefore}); their instants are ambiguous",
                    listOf(where.toString()),
                )
            }
            laterInOverlap = true
        }
        val offset = if (laterInOverlap) transition.offsetAfter else transition.offsetBefore
        return wall.toInstant(offset).toEpochMilli()
    }
}
