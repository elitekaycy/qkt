package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.instrument.EventTerms
import com.qkt.instrument.SlotSymbol
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.targetSymbol
import java.math.BigDecimal

/**
 * Translates `BUY`/`SELL`/`CLOSE` on an event slot into orders on real contracts when the rule fires, as option
 * structures do: the slot is a stream, never a tradable symbol. An order is placed on the contract the slot followed
 * for its latest ticks ([com.qkt.marketdata.source.MarketSource.slotContractAt]), so it trades the prices the rule
 * just read, after it has reduced what the slot holds: an order opposite to the book closes held contracts oldest
 * first at their own quotes, and only the remainder opens on the followed contract, so the book ([SlotBooks]) always
 * has one sign. A held contract whose trading has closed cannot be reduced; while one holds the other side, no
 * remainder opens. Market and limit orders translate; any other order type on a slot is suppressed with the reason.
 */
internal class SlotOrders(
    private val books: SlotBooks,
) {
    /** [entry]'s signals, translated onto contracts when [alias] is an event slot; unchanged otherwise. */
    fun route(
        alias: String,
        entry: (EvalContext) -> List<Signal>,
    ): (EvalContext) -> List<Signal> =
        { ec ->
            val slot = ec.streams[alias]?.qktSymbol?.takeIf { SlotSymbol.isSlot(it) }
            if (slot == null) entry(ec) else entry(ec).flatMap { translate(it, alias, slot, ec) }
        }

    /** `CLOSE` on [alias]: every contract of its book when it is an event slot, else [close]. */
    fun closeOr(
        alias: String,
        close: (EvalContext) -> List<Signal>,
    ): (EvalContext) -> List<Signal> =
        { ec ->
            val slot = ec.streams[alias]?.qktSymbol?.takeIf { SlotSymbol.isSlot(it) }
            if (slot == null) close(ec) else closeBook(alias, ec)
        }

    private class SlotOrder(
        val side: Side,
        val quantity: BigDecimal,
        val on: (contract: String, quantity: BigDecimal) -> Signal,
    )

    private fun translate(
        signal: Signal,
        alias: String,
        slot: String,
        ec: EvalContext,
    ): List<Signal> {
        if (signal.targetSymbol() != slot) return listOf(signal)
        val order =
            when (signal) {
                is Signal.Buy -> SlotOrder(Side.BUY, signal.size) { c, q -> signal.copy(symbol = c, size = q) }
                is Signal.Sell -> SlotOrder(Side.SELL, signal.size) { c, q -> signal.copy(symbol = c, size = q) }
                is Signal.Submit -> submitted(signal) ?: return listOf(refused(slot, alias, signal.request))
                else -> return listOf(signal)
            }
        return place(order, alias, slot, ec)
    }

    private fun submitted(signal: Signal.Submit): SlotOrder? =
        when (val r = signal.request) {
            is OrderRequest.Market ->
                SlotOrder(r.side, r.quantity) { c, q -> signal.copy(request = r.copy(symbol = c, quantity = q)) }
            is OrderRequest.Limit ->
                SlotOrder(r.side, r.quantity) { c, q -> signal.copy(request = r.copy(symbol = c, quantity = q)) }
            else -> null
        }

    private fun refused(
        slot: String,
        alias: String,
        request: OrderRequest,
    ): Signal =
        Signal.Suppressed(
            slot,
            "$alias is an event slot: it takes market and limit orders, not ${request::class.simpleName}",
        )

    private fun place(
        order: SlotOrder,
        alias: String,
        slot: String,
        ec: EvalContext,
    ): List<Signal> {
        val ctx = ec.strategyContext
        val now = ec.nowMs()
        val target =
            ctx.source.slotContractAt(slot, now)
                ?: return listOf(Signal.Suppressed(slot, "$alias follows no contract yet"))
        if (!trades(ctx, target, now)) {
            return listOf(Signal.Suppressed(slot, "$alias follows $target, which stopped trading"))
        }
        val reducing = if (order.side == Side.BUY) -1 else 1
        var rest = order.quantity
        var blocked = false
        val out = mutableListOf<Signal>()
        for (contract in books.held(ctx, alias, now)) {
            val held = books.quantity(ctx, contract)
            if (rest.signum() == 0 || held.signum() != reducing) continue
            if (!trades(ctx, contract, now)) {
                blocked = true
                continue
            }
            val take = rest.min(held.abs())
            out += if (order.side == Side.BUY) Signal.Buy(contract, take) else Signal.Sell(contract, take)
            rest = rest.subtract(take)
        }
        if (rest.signum() > 0 && !blocked) {
            out += order.on(target, rest)
            books.opened(ctx.strategyId, alias, target)
        }
        if (out.isEmpty()) out += Signal.Suppressed(slot, "$alias holds the other side in contracts awaiting expiry")
        return out
    }

    private fun closeBook(
        alias: String,
        ec: EvalContext,
    ): List<Signal> {
        val ctx = ec.strategyContext
        val now = ec.nowMs()
        return books.held(ctx, alias, now).flatMap { contract ->
            val held = books.quantity(ctx, contract)
            val cancel = Signal.CancelPendingForSymbol(contract, closing = true)
            when {
                held.signum() == 0 || !trades(ctx, contract, now) -> listOf(cancel)
                held.signum() > 0 -> listOf(cancel, Signal.Sell(contract, held))
                else -> listOf(cancel, Signal.Buy(contract, held.abs()))
            }
        }
    }

    /** True while [contract] still trades at [nowMs] (before its `closeAtMs`). */
    private fun trades(
        ctx: StrategyContext,
        contract: String,
        nowMs: Long,
    ): Boolean = (ctx.instruments.lookup(contract)?.derivative as? EventTerms)?.let { nowMs < it.closeAtMs } ?: false
}
