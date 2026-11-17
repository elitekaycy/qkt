package com.qkt.instrument.universe

/**
 * The word every instrument passing this filter has in [field] (lowercase), or null when the filter does not pin
 * one: a `field = word` clause on its own or under `AND`. A venue that can filter its listing narrows the read with
 * it; the filter itself still decides membership, so a venue that ignores the hint changes nothing.
 */
fun UniverseFilter.wordRequiredOf(field: CatalogField): String? =
    when (this) {
        is UniverseFilter.Compare ->
            (value as? CatalogValue.Str)?.value?.lowercase()?.takeIf { this.field == field && op == CompareOp.EQ }
        is UniverseFilter.And -> left.wordRequiredOf(field) ?: right.wordRequiredOf(field)
        else -> null
    }
