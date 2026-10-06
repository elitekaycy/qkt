package com.qkt.connector.gateway

import com.qkt.broker.PositionAccountingMode
import java.math.BigDecimal

/** What a gateway account last reported: equity, position accounting and net positions by venue code. */
internal class GatewayAccountState {
    @Volatile var equity: BigDecimal? = null
        private set

    @Volatile var accounting = PositionAccountingMode.UNKNOWN
        private set

    @Volatile var positions: Map<String, BigDecimal> = emptyMap()
        private set

    /** A hedging account's positions per ticket, by venue code: a `position` event is one ticket of a code. */
    @Volatile private var tickets: Map<String, Map<String, BigDecimal>> = emptyMap()

    /** Takes a resynchronization's [state]. */
    @Synchronized
    fun apply(state: GatewaySyncState) {
        equity = state.equity
        accounting = state.accounting
        positions = state.positions
        tickets = state.tickets
    }

    /** Takes a `position` event: a netting code's whole position, or one ticket of a hedging code's. */
    @Synchronized
    fun position(position: WirePosition) {
        val quantity = BigDecimal(position.quantity)
        val ticket = position.ticket
        if (ticket == null) {
            positions = positions + (position.symbol to quantity)
            return
        }
        val held =
            tickets[position.symbol].orEmpty().let {
                if (quantity.signum() ==
                    0
                ) {
                    it - ticket
                } else {
                    it + (ticket to quantity)
                }
            }
        tickets = tickets + (position.symbol to held)
        positions = positions + (position.symbol to held.values.fold(BigDecimal.ZERO, BigDecimal::add))
    }

    /** Takes an `account` event. */
    fun account(account: WireAccount) {
        equity = BigDecimal(account.equity)
    }

    /** What the account holds of venue [code], signed. */
    fun quantity(code: String): BigDecimal = positions[code] ?: BigDecimal.ZERO
}
