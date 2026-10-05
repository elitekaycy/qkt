package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * Excursion marks of one leg. [legId], [side] and [entryPrice] identify the leg the marks belong
 * to: a restore applies them only to that same leg, so a record left behind by a closed leg can
 * never inflate its successor's excursion.
 */
data class PersistedExcursion(
    val legId: String,
    val side: Side,
    val entryPrice: BigDecimal,
    val mfe: BigDecimal,
    val mae: BigDecimal,
    val adverseExtremePrice: BigDecimal?,
)
