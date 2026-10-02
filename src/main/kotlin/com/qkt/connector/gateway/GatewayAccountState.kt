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

    /** Takes a resynchronization's [state]. */
    fun apply(state: GatewaySyncState) {
        equity = state.equity
        accounting = state.accounting
        positions = state.positions
    }

    /** Takes a `position` event. */
    fun position(position: WirePosition) {
        positions = positions + (position.symbol to BigDecimal(position.quantity))
    }

    /** Takes an `account` event. */
    fun account(account: WireAccount) {
        equity = BigDecimal(account.equity)
    }

    /** What the account holds of venue [code], signed. */
    fun quantity(code: String): BigDecimal = positions[code] ?: BigDecimal.ZERO
}
