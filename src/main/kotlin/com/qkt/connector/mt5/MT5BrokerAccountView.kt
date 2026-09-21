package com.qkt.connector.mt5

import com.qkt.broker.BrokerAccountState
import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Clock
import java.math.BigDecimal

/**
 * The `/account` reads one MT5 login answers for the engine: equity, the full account
 * state, the netting/hedging mode, and a short-lived margin-level cache. Every read also
 * records the accounting mode, so the first successful `/account` call settles it for the
 * session, e.g. a hedging demo reports margin mode 2 once and [positionAccountingMode]
 * never asks the gateway again.
 */
internal class MT5BrokerAccountView(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val clock: Clock,
) {
    @Volatile
    private var accountingMode: PositionAccountingMode? = null

    @Volatile
    private var marginLevelCache: Pair<Long, BigDecimal?>? = null

    fun positionAccountingMode(): PositionAccountingMode =
        accountingMode
            ?: runCatching { client.getAccount() }
                .getOrNull()
                ?.let(::recordAccountingMode)
            ?: PositionAccountingMode.UNKNOWN

    fun accountEquity(): BigDecimal? =
        runCatching { client.getAccount() }
            .getOrNull()
            ?.also(::recordAccountingMode)
            ?.equity

    fun accountState(): BrokerAccountState? {
        val acct = runCatching { client.getAccount() }.getOrNull() ?: return null
        recordAccountingMode(acct)
        return BrokerAccountState(
            broker = profile.name.uppercase(),
            currency = acct.currency,
            balance = acct.balance,
            equity = acct.equity,
            margin = acct.margin,
            marginFree = acct.marginFree,
            openProfit = acct.profit,
            marginLevel = acct.marginLevel,
            login = acct.login,
            server = acct.server,
            name = acct.name,
        )
    }

    fun recordAccountingMode(account: MT5AccountInfo): PositionAccountingMode {
        val mode =
            when (account.marginMode) {
                MARGIN_MODE_NETTING -> PositionAccountingMode.NETTING
                MARGIN_MODE_HEDGING -> PositionAccountingMode.HEDGING
                else -> PositionAccountingMode.UNKNOWN
            }
        accountingMode = mode
        return mode
    }

    /**
     * Venue margin level, cached for [MARGIN_CACHE_TTL_MS] — the margin floor consults
     * this on every entry, and a synchronous /account round-trip per order would put
     * gateway latency on the approve path. The position poller keeps the cache warm via
     * [refreshMarginLevelIfStale], so in normal operation this never leaves the cache;
     * the synchronous fetch below is the fallback for a stale cache (poller not started,
     * out of session, or gateway hiccup) — never trade a margin gate on stale data.
     */
    fun marginLevel(): BigDecimal? {
        val now = clock.now()
        marginLevelCache?.let { (at, value) -> if (now - at < MARGIN_CACHE_TTL_MS) return value }
        val level = runCatching { client.getAccount()?.marginLevel }.getOrNull()
        marginLevelCache = now to level
        return level
    }

    /**
     * Poller-thread cache warmer: refreshes the margin cache once its TTL lapses. Only keeps
     * an already-populated cache warm — the first fetch stays on the first [marginLevel] read,
     * so sessions that never consult the margin floor never poll `/account` at all.
     */
    fun refreshMarginLevelIfStale() {
        val (at, _) = marginLevelCache ?: return
        val now = clock.now()
        if (now - at < MARGIN_CACHE_TTL_MS) return
        val level = runCatching { client.getAccount()?.marginLevel }.getOrNull()
        marginLevelCache = now to level
    }

    private companion object {
        /** Margin-level cache TTL — fresh enough for a floor check, cheap on the gateway. */
        const val MARGIN_CACHE_TTL_MS: Long = 5_000L
    }
}
