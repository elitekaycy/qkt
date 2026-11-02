package com.qkt.connector.mt5

/** Outcome of correlating a venue-side MT5 comment against this broker's strategy. */
internal sealed interface OrphanMatch {
    /** Comment unambiguously belongs to [strategyName]. Safe to seed. */
    data object Match : OrphanMatch

    /** Comment is a strict prefix of `dsl-<strategyName>` — likely truncation cut us short. Seed with WARN. */
    data object AmbiguousTruncation : OrphanMatch

    /**
     * Comment starts with `dsl-<strategyName>-` followed by non-digits — could be a longer
     * strategy name (e.g. `ema-cross` matching an `ema-cross-v2` position). Skip + WARN.
     */
    data class AmbiguousOverlap(
        val tail: String,
    ) : OrphanMatch

    /**
     * Comment is a truncated prefix that this strategy could claim, but one or more
     * [siblings] on the same magic could also claim it (their `dsl-<name>` prefixes
     * are also consistent with the truncated comment). Skip + WARN — neither strategy
     * may seed without risking double-attribution on close. See #154.
     */
    data class ConflictWithSibling(
        val siblings: List<String>,
    ) : OrphanMatch

    /** Comment is null, neither `dsl-` nor `ORD-`, or matches a different strategy entirely. Skip silently. */
    data object NotOurs : OrphanMatch
}

/**
 * Correlate an MT5 [comment] back to [strategyName].
 *
 * MT5 truncates comments. Original comments take the shape `dsl-<strategy>-<n>` for an order the DSL manages, and
 * `ORD-<strategy>-<n>` for a session's plain order, whose session of several strategies names them all
 * (`ORD-a+b-<n>`); either may be `oco:<parent>/<id>` for an OCO leg. We strip the optional `oco:<X>/` prefix, then
 * classify the result by how it relates to `dsl-<strategyName>` or `ORD-<strategyName>`. A session id of several
 * strategies cannot say whose order it was: a strategy it names claims it only when no sibling it also names
 * shares this magic ([OrphanMatch.ConflictWithSibling] otherwise), so two strategies never seed one position.
 *
 * [siblings] is the list of other strategy names sharing this magic. When non-empty and
 * the primary match is [OrphanMatch.AmbiguousTruncation], we cross-check: if any sibling's
 * `dsl-<sibling>` prefix is also consistent with the comment, we return
 * [OrphanMatch.ConflictWithSibling] so the caller can refuse to seed. When the primary
 * match is [OrphanMatch.AmbiguousOverlap], a sibling that produces an unambiguous
 * [OrphanMatch.Match] for the same comment quietly downgrades us to [OrphanMatch.NotOurs] —
 * the sibling is the real owner.
 */
internal fun matchOrphan(
    comment: String?,
    strategyName: String,
    siblings: List<String> = emptyList(),
): OrphanMatch {
    if (comment.isNullOrEmpty()) return OrphanMatch.NotOurs
    val inner = stripOcoPrefix(comment)
    if (!inner.startsWith(DSL_PREFIX) && !inner.startsWith(SESSION_PREFIX)) return OrphanMatch.NotOurs

    val primary = classifyAgainst(inner, strategyName)

    return when (primary) {
        is OrphanMatch.Match -> {
            val conflicts = if (namesSeveral(inner)) claimants(inner, strategyName, siblings) else emptyList()
            if (conflicts.isNotEmpty()) OrphanMatch.ConflictWithSibling(conflicts) else primary
        }
        is OrphanMatch.AmbiguousTruncation -> {
            val conflicts = claimants(inner, strategyName, siblings)
            if (conflicts.isNotEmpty()) OrphanMatch.ConflictWithSibling(conflicts) else primary
        }
        is OrphanMatch.AmbiguousOverlap -> {
            val unambiguousOwner =
                siblings.any { s -> s != strategyName && classifyAgainst(inner, s) is OrphanMatch.Match }
            if (unambiguousOwner) OrphanMatch.NotOurs else primary
        }
        else -> primary
    }
}

private const val DSL_PREFIX = "dsl-"
private const val SESSION_PREFIX = "ORD-"
private const val SESSION_NAME_JOIN = '+'
private val SEQ_TAIL = Regex("-{1,2}\\d*")

private fun namesSeveral(inner: String) = inner.startsWith(SESSION_PREFIX) && SESSION_NAME_JOIN in inner

/** The [siblings] other than [strategyName] the comment could also belong to. */
private fun claimants(
    inner: String,
    strategyName: String,
    siblings: List<String>,
): List<String> =
    siblings.filter { s ->
        s != strategyName &&
            classifyAgainst(inner, s).let { it is OrphanMatch.Match || it is OrphanMatch.AmbiguousTruncation }
    }

/** Single-strategy classification step, with [inner] already past `oco:` prefix. */
private fun classifyAgainst(
    inner: String,
    strategyName: String,
): OrphanMatch {
    if (inner.startsWith(DSL_PREFIX)) return classifyNamed(inner, "$DSL_PREFIX$strategyName")
    val names = inner.removePrefix(SESSION_PREFIX).split(SESSION_NAME_JOIN)
    if (names.size == 1) return classifyNamed(inner, "$SESSION_PREFIX$strategyName")
    if (strategyName in names.dropLast(1)) return OrphanMatch.Match
    // Only the last name carries the order number, and only it can be cut by the venue.
    return if (names.last().isEmpty()) OrphanMatch.NotOurs else classifyNamed(names.last(), strategyName)
}

/** How [strippedComment] relates to [expected], an id's prefix up to the strategy's name. */
private fun classifyNamed(
    strippedComment: String,
    expected: String,
): OrphanMatch {
    if (strippedComment.startsWith(expected)) {
        val tail = strippedComment.substring(expected.length)
        return when {
            tail.isEmpty() -> OrphanMatch.Match
            // The DSL id generator emits `dsl-<name>-` + `-<seq>`, so an own order id has a
            // DOUBLE-dash tail (`--0`). Accept one or two dashes followed by digits — including
            // a bare dash run when MT5's 16-char truncation cut the counter off. A tail whose
            // first non-dash character is anything else still means a longer strategy name.
            tail.matches(SEQ_TAIL) -> OrphanMatch.Match
            else -> OrphanMatch.AmbiguousOverlap(tail)
        }
    }
    if (expected.startsWith(strippedComment)) return OrphanMatch.AmbiguousTruncation
    return OrphanMatch.NotOurs
}

private fun stripOcoPrefix(comment: String): String {
    if (!comment.startsWith("oco:")) return comment
    val slash = comment.indexOf('/')
    return if (slash >= 0) comment.substring(slash + 1) else comment.substring(4)
}
