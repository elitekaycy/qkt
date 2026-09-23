package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal

/** The order manager's dependencies and policy hooks, as documented on `OrderManager`'s constructor. */
internal class OrderSettings(
    val broker: Broker,
    val bus: EventBus,
    val priceProvider: MarketPriceProvider,
    val clock: Clock,
    val persistor: StatePersistor,
    val closeTicketFor: ((String, String) -> String?)?,
    val closePrimaryTicketFor: ((String, String) -> String?)?,
    val requireArmedTrailTicket: Boolean,
    val instruments: InstrumentRegistry,
    val trackRisk: Boolean,
    val onProtectionFailure: (strategyId: String, message: String) -> Unit,
    val engineHeldSubmissionBlockReason: (OrderRequest) -> String?,
    val isRiskReducingForHalt: (OrderRequest) -> Boolean,
    val strategyNetQty: ((strategyId: String, symbol: String) -> BigDecimal)?,
    val positionMode: (symbol: String) -> PositionAccountingMode,
    val bookedVenueTickets: (strategyId: String) -> Set<String>,
    val openLegQuantity: ((strategyId: String, legId: String) -> BigDecimal?)?,
) {
    /**
     * The venue ticket an engine-managed exit closes: its own leg's ticket when it has one, else
     * the strategy's primary position on the symbol. Null means it closes by side (netting).
     */
    fun closeTicket(request: OrderRequest): String? =
        closeTicketFor?.invoke(request.strategyId, request.id)
            ?: closePrimaryTicketFor?.invoke(request.strategyId, request.symbol)
}
