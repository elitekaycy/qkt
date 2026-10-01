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
 * not expired, market or limit, a quantity floored to `volumeStep` and at least `volumeMin`, and
 * (long only) a sell no larger than what is held less pending sells. A limit is snapped on the price
 * grid so it never fills early: buys down, sells up. A DAY order lapses at the session's end.
 */
internal class OptionOrderEntry(
    private val instruments: InstrumentRegistry,
    private val clock: Clock,
    private val calendar: TradingCalendar,
    private val positions: OptionPositions,
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
        val terms = meta?.derivative as? OptionTerms ?: return Checked.Refused("$symbol is not a catalogued option")
        val root =
            instruments.options()?.optionRoot(symbol) ?: return Checked.Refused("$symbol is not a catalogued option")
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
        if (request.side == Side.SELL) {
            val pending =
                working
                    .filter {
                        it.request.symbol == symbol &&
                            it.request.strategyId == request.strategyId &&
                            it.request.side == Side.SELL
                    }.fold(BigDecimal.ZERO) { sum, o -> sum.add(o.request.quantity) }
            val available = positions.of(request.strategyId, symbol).subtract(pending)
            if (quantity > available) {
                return Checked.Refused(
                    "sell of $quantity $symbol exceeds the $available held; the option venue opens no short positions",
                )
            }
        }
        val sized =
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
                    if (limit.signum() <=
                        0
                    ) {
                        return Checked.Refused(
                            "limit ${request.limitPrice} on $symbol snaps to no price above zero",
                        )
                    }
                    request.copy(quantity = quantity, limitPrice = limit)
                }
                else -> return Checked.Refused(
                    "the option venue takes market and limit orders, not ${request::class.simpleName}",
                )
            }
        val lapse =
            request.expiresAt
                ?: if (request.timeInForce ==
                    TimeInForce.DAY
                ) {
                    calendar.sessionRange(symbol, Instant.ofEpochMilli(now)).to.toEpochMilli()
                } else {
                    null
                }
        return Checked.Accepted(WorkingOption(sized, root, now, lapse, terms.expiryMs))
    }
}
