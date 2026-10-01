package com.qkt.broker.options

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.OptionTerms
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** An accepted option order: [request] as sized and snapped, its [root], when it arrived and when it lapses. */
internal class WorkingOption(
    val request: OrderRequest,
    val root: OptionRoot,
    val submittedAt: Long,
    val expiresAt: Long?,
    val contractExpiryMs: Long,
)

/**
 * The option venue's checks on an incoming order: a catalogued option of a root that trades a chain,
 * not expired, market or limit, and a quantity floored to `volumeStep` and at least `volumeMin`
 * (whether a short can be carried is the margin rule's call, before the order reaches the venue).
 * A limit is snapped on the price
 * grid so it never fills early: buys down, sells up. A DAY order lapses at the session's end.
 */
internal class OptionOrderEntry(
    private val instruments: InstrumentRegistry,
    private val clock: Clock,
    private val calendar: TradingCalendar,
) {
    /** The outcome of [check]. */
    sealed interface Checked {
        /** The order may work as [order]. */
        data class Accepted(
            val order: WorkingOption,
        ) : Checked

        /** The order is refused for [reason]. */
        data class Refused(
            val reason: String,
        ) : Checked
    }

    /** Checks [request] against the venue's rules, given the orders already [working]. */
    fun check(
        request: OrderRequest,
        working: Collection<WorkingOption>,
    ): Checked {
        val symbol = request.symbol
        val meta = instruments.lookup(symbol)
        val terms = meta?.derivative as? OptionTerms
        val root = instruments.options()?.optionRoot(symbol)
        if (meta == null || terms == null || root == null) return Checked.Refused("$symbol is not a catalogued option")
        if (root.chains ==
            null
        ) {
            return Checked.Refused("${root.root} declares no chain series to trade on (chains: trade | book)")
        }
        val now = clock.now()
        if (now >= terms.expiryMs) return Checked.Refused("$symbol expired at ${Instant.ofEpochMilli(terms.expiryMs)}")
        val quantity = request.quantity.divide(meta.volumeStep, 0, RoundingMode.DOWN).multiply(meta.volumeStep)
        if (quantity.signum() == 0 || quantity < meta.volumeMin) {
            return Checked.Refused("quantized volume $quantity below venue volumeMin ${meta.volumeMin} for $symbol")
        }
        val sized = sized(request, quantity, terms) ?: return Checked.Refused(unsizable(request))
        return Checked.Accepted(WorkingOption(sized, root, now, lapseOf(request, now), terms.expiryMs))
    }

    /** [request] at [quantity], a limit snapped so it never fills early; null when it cannot work here. */
    private fun sized(
        request: OrderRequest,
        quantity: BigDecimal,
        terms: OptionTerms,
    ): OrderRequest? =
        when (request) {
            is OrderRequest.Market -> request.copy(quantity = quantity)
            is OrderRequest.Limit -> {
                val grid = terms.tickSteps
                val limit =
                    if (request.side ==
                        Side.BUY
                    ) {
                        grid.floor(request.limitPrice)
                    } else {
                        grid.ceil(request.limitPrice)
                    }
                if (limit.signum() > 0) request.copy(quantity = quantity, limitPrice = limit) else null
            }
            else -> null
        }

    private fun unsizable(request: OrderRequest): String =
        if (request is OrderRequest.Limit) {
            "limit ${request.limitPrice} on ${request.symbol} snaps to no price above zero"
        } else {
            "the option venue takes market and limit orders, not ${request::class.simpleName}"
        }

    /** When [request] lapses: its own expiry, the session's end for a DAY order, else never. */
    private fun lapseOf(
        request: OrderRequest,
        now: Long,
    ): Long? {
        request.expiresAt?.let { return it }
        if (request.timeInForce != TimeInForce.DAY) return null
        return calendar.sessionRange(request.symbol, Instant.ofEpochMilli(now)).to.toEpochMilli()
    }
}
