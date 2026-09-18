package com.qkt.connectivity

/**
 * A technology qkt trades through — MetaTrader 5 via mt5-gateway, the Bybit v5 API, Rithmic.
 *
 * One implementation per connector type, discovered by [ConnectorRegistry]. A connector knows how
 * to open trading accounts of its type; everything specific to it stays in its own package, and
 * the rest of qkt reaches it only through [TradingAccount].
 */
interface Connector {
    /** What this connector is and what it can trade. */
    val spec: ConnectorSpec

    /**
     * Opens every configured account of this connector's type in one call, so accounts that point
     * at the same gateway or credentials can share one connection. Performs no network I/O —
     * [TradingAccount.verify] is where a connector first talks to the venue. Returns one account
     * per entry in [accounts], in the same order.
     */
    fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount>
}

/**
 * A connector's identity: the lowercase [type] that selects it in config (`type: mt5`), a
 * human-readable [displayName], and the [productTypes] it can trade.
 */
data class ConnectorSpec(
    val type: String,
    val displayName: String,
    val productTypes: Set<ProductType>,
) {
    init {
        require(type.isNotBlank()) { "ConnectorSpec.type must not be blank" }
        require(type == type.lowercase()) { "ConnectorSpec.type must be lowercase: $type" }
    }
}

/** The kinds of product a venue lists. */
enum class ProductType {
    /** A contract for difference quoted by a broker, e.g. XAUUSD on an MT5 account. */
    CFD,

    /** Buying and selling the asset itself, e.g. BTCUSDT spot. */
    SPOT,

    /** A futures contract with no expiry, financed by periodic funding, e.g. a BTCUSDT perpetual. */
    PERPETUAL,

    /** A dated futures contract that expires and settles, e.g. CME MES or a BTC quarterly. */
    FUTURE,
}
