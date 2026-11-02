package com.qkt.persistence

import com.qkt.broker.BookedDeals

/**
 * Storage for what a session booked of its venue's deals ([BookedDeals]), under its state owner [ownerId]. Kept
 * apart from the rest of [StatePersistor] so the defaults leave persistors that predate it compiling; a persistor
 * that keeps nothing states no deal from before a restart.
 */
interface BookedDealPersistence {
    /** Persist [deals], replacing the last save. */
    fun saveBookedDeals(
        ownerId: String,
        deals: BookedDeals,
    ) {}

    /** The last deals saved for [ownerId], or null when none were. */
    fun loadBookedDeals(ownerId: String): BookedDeals? = null
}
