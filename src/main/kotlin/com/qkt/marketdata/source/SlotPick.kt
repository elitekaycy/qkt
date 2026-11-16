package com.qkt.marketdata.source

import com.qkt.instrument.FamilyEvent
import com.qkt.instrument.SlotCandidate
import com.qkt.instrument.SlotSymbol
import com.qkt.instrument.StrikeRule
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * The contract an event slot follows given its family's open events and quotes at one instant, as
 * `OptionSelector.select` picks a leg: one snapshot, the slot's event by close order, then its strike rule over the
 * contracts with a usable two-sided quote (a bid at or below the ask), in strike order. An event with one contract
 * resolves to it whatever the strike part says. Null when the event or a fitting contract is missing.
 */
internal object SlotPick {
    fun pick(
        slot: SlotSymbol,
        rule: StrikeRule,
        events: List<FamilyEvent>,
        quote: (String) -> Tick?,
    ): String? = events.getOrNull(slot.eventOffset)?.let { pickIn(it, rule, quote) }

    /** The contract of [event] that [rule] picks by the quotes [quote] gives, its only contract when it has one. */
    fun pickIn(
        event: FamilyEvent,
        rule: StrikeRule,
        quote: (String) -> Tick?,
    ): String? {
        event.contracts.singleOrNull()?.let { return it.symbol }
        val candidates =
            event.contracts.mapNotNull { contract ->
                usableMid(quote(contract.symbol))?.let { SlotCandidate(contract.symbol, it) }
            }
        return rule.pick(candidates)?.symbol
    }

    private fun usableMid(tick: Tick?): BigDecimal? {
        val bid = tick?.bid ?: return null
        val ask = tick.ask ?: return null
        return if (bid > ask) null else tick.mid
    }
}
