package com.qkt.connectivity

/**
 * A technology qkt trades through — MetaTrader 5 via mt5-gateway, a VGP venue gateway
 * (Bybit, Deribit, …), Rithmic.
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

    /**
     * The trading hours each of [accounts] trades on, by entry name, read from config alone: no secrets, no network,
     * the same calendars [TradingAccount.tradingHours] returns once the account is opened. A backtest takes a venue's
     * calendars from here, so it judges sessions as the live account will. Empty when this connector cannot say
     * without opening the account.
     */
    fun tradingHours(accounts: List<AccountConfig>): Map<String, com.qkt.common.SymbolCalendars> = emptyMap()

    /**
     * The trading hours of the venue profiles this connector ships built in (MT5's `exness`, `the5ers`, ...), by
     * name: what an entry of that name, or one extending it without `calendars:`, trades on. A backtest whose stream
     * prefix names one takes these calendars with no config entry.
     */
    fun builtInTradingHours(): Map<String, com.qkt.common.SymbolCalendars> = emptyMap()
}

/**
 * A connector's identity: the lowercase [type] that selects it in config (`type: mt5`), a
 * human-readable [displayName], the [productTypes] it can trade, and the [settings] its `brokers:`
 * entries may carry beyond [AccountSettings.SHARED] — any other key is refused at load.
 */
data class ConnectorSpec(
    val type: String,
    val displayName: String,
    val productTypes: Set<ProductType>,
    val settings: Set<String>,
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

    /** A dated option that expires and settles at its intrinsic value, e.g. a Deribit BTC_USDC call. */
    OPTION,

    /** A share of a company held outright, e.g. AAPL at a stock broker (not a CFD on it). */
    EQUITY,
}
