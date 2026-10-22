package com.qkt.observe.insights

/**
 * Where each venue account's deal poll reads from, and which deals it already sent. Every read starts
 * [overlapMs] before the time the last successful read reached, so a deal the venue lists late (behind
 * a newer one, or after its reads failed for a while) is still read; each deal is sent once, by its
 * ticket. The start moves only when a read succeeded ([read]); an account's first read reaches back
 * [backfillMs]. Used from one poller thread.
 */
internal class DealCursor(
    private val backfillMs: Long,
    private val overlapMs: Long,
) {
    private val readTo = HashMap<String, Long>()
    private val seeds = HashMap<String, Long>()
    private val sent = HashMap<String, MutableMap<String, Long>>()

    /** Where [account]'s next read starts. */
    fun from(
        account: String,
        nowMs: Long,
    ): Long = readTo[account]?.let { it - overlapMs } ?: seeds.getOrPut(account) { nowMs - backfillMs }

    /** Whether deal [ticket] of [account] was already sent. */
    fun wasSent(
        account: String,
        ticket: String,
    ): Boolean = sent[account]?.containsKey(ticket) == true

    /** Deal [ticket] of [account], booked at [tsMs], was sent. */
    fun sent(
        account: String,
        ticket: String,
        tsMs: Long,
    ) {
        sent.getOrPut(account) { HashMap() }[ticket] = tsMs
    }

    /** A read of [account] up to [nowMs] succeeded; deals older than the next read's start are forgotten. */
    fun read(
        account: String,
        nowMs: Long,
    ) {
        readTo[account] = maxOf(readTo[account] ?: nowMs, nowMs)
        val start = from(account, nowMs)
        sent[account]?.values?.removeIf { it < start }
    }
}
