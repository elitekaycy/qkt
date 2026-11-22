package com.qkt.dsl.sets

import com.qkt.dsl.parse.TokenCursor
import com.qkt.dsl.parse.TokenKind
import com.qkt.dsl.parse.slotSelector
import com.qkt.instrument.SlotSelectors

/** Where a filtered set looks after `VENUE:`: every [family] (null) or one, and its event [selector], if any. */
internal data class SetScope(
    val family: String?,
    val selector: String?,
)

/** The event selector that takes every open event of a family: only with `.*` (`@each.*`). */
internal const val EACH_EVENT: String = "each"

/**
 * True when the cursor, just after `VENUE:`, is on an event ladder set of one family (`KXS@front.*`): the family,
 * `@`, an event selector, `.` and `*`. A slot stream (`KXS@front.p70`) ends in a strike instead.
 */
internal fun TokenCursor.atLadderSet(): Boolean =
    peek().kind == TokenKind.IDENT &&
        peekAtOrNull(1)?.kind == TokenKind.AT_SIGN &&
        peekAtOrNull(3)?.kind == TokenKind.DOT &&
        peekAtOrNull(4)?.kind == TokenKind.STAR

/**
 * The scope of a filtered set after `VENUE:`: `*` (every instrument), `*@<event>.<strike>` (a slot universe over the
 * families), `*@<event>.*` (every contract of each family's selected event) or `<family>@<event>.*` (one family's).
 * `<event>` is `front`, `next` or, with `.*` only, `each` (every open event).
 */
internal fun TokenCursor.setScope(): SetScope {
    val family = if (match(TokenKind.STAR)) null else expect(TokenKind.IDENT, "expected '*' or a family").lexeme
    if (!match(TokenKind.AT_SIGN)) {
        if (family != null) this.error("expected '@<event>.*' after the family '$family'")
        return SetScope(null, null)
    }
    val event = expectName("expected an event selector after '@'").lexeme
    if (peekAtOrNull(1)?.kind == TokenKind.STAR && peek().kind == TokenKind.DOT) {
        if (event != EACH_EVENT && SlotSelectors.eventOffset(event) == null) {
            this.error("unknown event selector '@$event'; use ${SlotSelectors.eventSpellings}, @$EACH_EVENT")
        }
        advance()
        advance()
        return SetScope(family, "$event.*")
    }
    if (family != null) this.error("a family's set takes every contract of an event: '$family@$event.*'")
    if (event == EACH_EVENT) this.error("@$EACH_EVENT takes every contract of each open event: '*@$EACH_EVENT.*'")
    return SetScope(null, slotSelector(event))
}
