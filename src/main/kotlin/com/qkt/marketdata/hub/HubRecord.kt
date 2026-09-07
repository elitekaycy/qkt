package com.qkt.marketdata.hub

import java.math.BigDecimal

/**
 * One fact read from a qkt-data-hub store, as it was knowable at [knownAt].
 *
 * The engine reads exactly one field to decide visibility: [knownAt]. [effectiveAt] says when the
 * fact applies and may be in the future — that is what lets a strategy legitimately know on Monday
 * that a release lands on Friday without also knowing Friday's number. Keeping the two apart is
 * the whole reason this type is not just a `Tick`.
 *
 * Values arrive as `BigDecimal` because the hub writes decimal text, never floats: a fact must not
 * change because it crossed a machine boundary. A `null` value means the hub recorded the field as
 * unknown, and it stays unknown — the engine renders it as `Undefined`, never as zero.
 */
data class HubRecord(
    val dataset: String,
    val scope: String,
    val key: String,
    val revision: Int,
    val knownAt: Long,
    val effectiveAt: Long,
    val availability: String,
    val source: String,
    val seq: Long,
    val fields: Map<String, BigDecimal?>,
    val periodStart: Long? = null,
    val periodEnd: Long? = null,
) {
    init {
        require(dataset.isNotBlank()) { "HubRecord.dataset must not be blank" }
        require(scope.isNotBlank()) { "HubRecord.scope must not be blank" }
        require(revision >= 1) { "HubRecord.revision must be >= 1, got $revision" }
    }

    /** True when this record is a backfilled estimate rather than something we observed. */
    val isDerived: Boolean get() = availability == AVAILABILITY_DERIVED

    companion object {
        const val AVAILABILITY_OBSERVED: String = "observed"
        const val AVAILABILITY_PUBLISHED: String = "published"
        const val AVAILABILITY_DERIVED: String = "derived"
    }
}
