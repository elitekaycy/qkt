package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.broker.BrokerPendingOrder
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.CloseReason
import com.qkt.broker.VenueIdentity
import com.qkt.broker.VenueLeg
import com.qkt.common.Side
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/**
 * A gateway account's venue truth in the engine's terms ([com.qkt.broker.Broker.accountState] and the
 * reads beside it), mapped once by [GatewayDirectReads] and given qkt symbols and the facts VGP v1 does not
 * carry. A netting position's ticket is qkt's id for it ([VenueLeg]: a new one each time the account's
 * position crosses zero), owned by the strategy that booked it, else by the one broker of the account whose
 * session holds it exactly ([holderOf]: a continuous stream's lane holds the dated contract, which is then the
 * stream's position as qkt booked it, under the id its deals carry), and its open time qkt's when the venue
 * states none; its mark and floating P&L stay null here (VGP v1 carries neither) for the state poller to value
 * from the session's quotes. A deal's leg, position and realized P&L are what qkt's accounting booked for that
 * fill, found by its `fill_id` ([GatewayFillLegs]). A deal qkt did not book in this process (placed elsewhere, or
 * before a restart)
 * has no realized P&L to state and is left out; one younger than [accountingGraceMs] may still be on its
 * way to the books, so it and every deal after it wait for the next read.
 */
internal class GatewayVenueReads(
    private val venue: VenueIdentity,
    private val reads: GatewayDirectReads,
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
    private val legs: GatewayFillLegs,
    private val held: PositionProvider,
    private val strategy: String?,
    private val ownerOf: (String) -> String?,
    private val holderOf: (String, BigDecimal) -> GatewayRouting.Holding? = { _, _ -> null },
    private val accountingGraceMs: Long = 60_000L,
) {
    fun account(): BrokerAccountState {
        val a = reads.account()
        return BrokerAccountState(
            venue.label,
            a.currency,
            a.balance,
            a.equity,
            a.margin,
            a.freeMargin,
            a.openProfit,
            a.marginLevel,
            login = a.login,
            server = "",
            name = venue.label,
            venue = venue,
        )
    }

    fun positions(): List<BrokerPositionTicket> =
        reads.positions().map { p ->
            val symbol = symbols.qkt(p.symbol)
            val netting = p.ticket == p.symbol
            val booked = legs.open(symbol)
            val mine = held.positionFor(symbol)?.takeIf { it.quantity.abs().compareTo(p.lots) == 0 }
            // A continuous stream's contract is held by another broker of the account, its lane: the position is
            // the stream's, as qkt booked it (its deals name that position), across every roll.
            val lane = if (booked == null && mine == null) holderOf(symbol, p.lots) else null
            val stream = lane?.stream?.let(legs::open)
            val ticket =
                when {
                    !netting -> p.ticket
                    else ->
                        booked?.positionId ?: stream?.positionId ?: (mine ?: lane?.streamPosition ?: lane?.position)
                            ?.let(VenueLeg::idOf) ?: "$symbol#${p.openedAtMs}"
                }
            BrokerPositionTicket(
                ticket,
                symbol,
                Side.valueOf(p.side),
                p.lots,
                p.entry,
                null,
                null,
                null,
                // The paper adapter states no open time; qkt's own booking of the position has it.
                p.openedAtMs ?: (booked?.openedAt ?: stream?.openedAt ?: mine?.openedAt)?.takeIf { netting },
                null,
                strategyId =
                    booked?.owners?.singleOrNull() ?: strategy?.takeIf { mine != null }
                        ?: stream?.owners?.singleOrNull() ?: lane?.strategy,
            )
        }

    fun pendingOrders(): List<BrokerPendingOrder> =
        reads.pendingOrders().map { o ->
            BrokerPendingOrder(
                o.ticket,
                symbols.qkt(o.symbol),
                if (o.type.startsWith("buy")) Side.BUY else Side.SELL,
                o.type,
                o.lots,
                o.price,
                comment = o.comment,
                clientOrderId = o.ticket,
                strategyId = ownerOf(o.ticket),
            )
        }

    fun deals(
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<BrokerDeal> {
        val out = mutableListOf<BrokerDeal>()
        for (fill in client.deals(fromMs, toMs)) {
            val engineId = GatewayClientIds.engineId(fill.clientOrderId)
            val fact = legs.of(fill.fillId, engineId)
            if (fact == null) {
                if (fill.time > nowMs - accountingGraceMs) break
                continue
            }
            out += deal(fill, engineId, fact)
        }
        return out
    }

    private fun deal(
        fill: WireFill,
        engineId: String,
        fact: GatewayLegFact,
    ): BrokerDeal {
        val base = reads.deal(fill)
        val costs = WireValues.costsOf(fill.costs, fill.time)

        fun paid(vararg kinds: CostKind): BigDecimal? =
            costs.filter { it.kind in kinds }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { s, c ->
                s - c.amount.amount
            }
        return BrokerDeal(
            broker = venue.label,
            dealTicket = base.dealTicket,
            positionTicket = fact.leg.positionId,
            orderTicket = fill.venueOrderId,
            symbol = symbols.qkt(fill.symbol),
            side = Side.valueOf(base.side),
            entry = fact.leg.entry,
            qty = base.lots,
            price = base.price,
            profit = fact.grossRealized,
            commission = paid(CostKind.COMMISSION) ?: BigDecimal.ZERO,
            swap = paid(CostKind.SWAP, CostKind.FUNDING) ?: BigDecimal.ZERO,
            magic = null,
            comment = fill.clientOrderId,
            ts = fill.time,
            fee = paid(CostKind.EXCHANGE_FEE, CostKind.TAX),
            clientOrderId = fill.clientOrderId,
            closeReason = CloseReason.of(null, engineId).takeIf { fact.leg.entry != "IN" },
            costs = costs,
            strategyId = fact.strategyId,
            realizedSource = "engine",
            nextPositionTicket = fact.leg.nextPositionId,
        )
    }
}
