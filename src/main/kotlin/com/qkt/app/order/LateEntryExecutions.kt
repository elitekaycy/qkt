package com.qkt.app.order

import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * Protects what a decomposed bracket entry executes after it ended. E.g. entry e1 of bracket b1
 * filled 30 of 50 and was cancelled, so b1's exits were armed for 30; the venue then reports 10 more
 * that executed before the cancel took effect. The ledger books those 10, so they get b1's exits
 * too, as their own OCO (`b1-late1-sl`, `b1-late1-tp`) sized to them and anchored on their price.
 * Ended entries are kept for [RETAINED] entries, well past any late report.
 */
internal class LateEntryExecutions(
    private val exits: BracketExits,
    private val ops: OrderOps,
    private val log: Logger,
) {
    private class Ended(
        val bracket: OrderRequest.Bracket,
        var covered: BigDecimal,
        var late: Int = 0,
    )

    private val ended =
        object : LinkedHashMap<String, Ended>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ended>?) = size > RETAINED
        }

    /** Entry [entryId] of [bracket] ended with [covered] filled, all of it protected by armed exits. */
    fun remember(
        entryId: String,
        bracket: OrderRequest.Bracket,
        covered: BigDecimal,
    ) {
        ended[entryId] = Ended(bracket, covered)
    }

    /**
     * The venue reported [quantity] more of the ended entry [entryId] at [price], [cumulative] in all
     * when it says. Arms exits for what is not yet covered; true when [entryId] is a remembered entry.
     */
    fun onLateExecution(
        entryId: String,
        quantity: BigDecimal,
        price: BigDecimal,
        cumulative: BigDecimal?,
    ): Boolean {
        val entry = ended[entryId] ?: return false
        val total = cumulative ?: entry.covered.add(quantity)
        val uncovered = total.subtract(entry.covered)
        if (uncovered.signum() <= 0) return true
        entry.covered = total
        entry.late += 1
        val bracket = entry.bracket.copy(id = "${entry.bracket.id}-late${entry.late}")
        log.warn(
            "entry {} executed {} after it ended; arming exits {} for it",
            entryId,
            uncovered.toPlainString(),
            bracket.id,
        )
        ops.dispatch(exits.exitOco(bracket, price, uncovered))
        return true
    }

    private companion object {
        const val RETAINED = 1_000
    }
}
