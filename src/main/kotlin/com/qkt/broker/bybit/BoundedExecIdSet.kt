package com.qkt.broker.bybit

import java.util.Collections

/**
 * A thread-safe [MutableSet] of execution ids that holds at most [maxSize] entries,
 * evicting the oldest (insertion order) when full. Used to dedup fills seen during
 * reconciliation so a 24/7 broker session does not accumulate every execution id forever.
 *
 * An id evicted and then re-seen counts as new again — harmless, because eviction only
 * happens far beyond the recovery window, by which point a repeat is not a real duplicate.
 *
 * e.g. boundedExecIdSet(3): add a,b,c then d → a is evicted; add("a") returns true again.
 *
 * The default [maxSize] of 50_000 is far larger than any reconciliation window's fill count
 * (so it never evicts a genuine in-window duplicate) yet caps memory at a few MB.
 */
fun boundedExecIdSet(maxSize: Int = 50_000): MutableSet<String> =
    Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                    size > maxSize
            },
        ),
    )
