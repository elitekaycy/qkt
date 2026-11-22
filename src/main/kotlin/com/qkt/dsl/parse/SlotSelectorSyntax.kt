package com.qkt.dsl.parse

import com.qkt.instrument.SlotSelectors

/**
 * An event slot's `<event>.<strike>` (`front.p70`) after its [event] selector was read and with the cursor on the
 * `.`, each part validated against [SlotSelectors]: shared by a slot stream (`KALSHI:KXINXU@front.p70`) and a slot
 * universe (`KALSHI:*@front.p70 WHERE ...`).
 */
internal fun TokenCursor.slotSelector(event: String): String {
    if (SlotSelectors.eventOffset(event) == null) {
        this.error("unknown slot event selector '@$event'; use ${SlotSelectors.eventSpellings}")
    }
    expect(TokenKind.DOT, "expected '.<strike>' after '@$event'")
    val strike = expect(TokenKind.IDENT, "expected a slot strike selector after '@$event.'").lexeme
    if (SlotSelectors.strike(strike) == null) {
        this.error("unknown slot strike selector '$strike'; use ${SlotSelectors.strikeSpellings}")
    }
    return "$event.$strike"
}
