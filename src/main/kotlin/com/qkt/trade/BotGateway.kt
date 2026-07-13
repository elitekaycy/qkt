package com.qkt.trade

import com.qkt.broker.mt5.MT5AccountInfo
import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.broker.mt5.MT5Client
import com.qkt.broker.mt5.MT5Deal
import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.broker.mt5.MT5OrderTranslator
import com.qkt.broker.mt5.MT5PendingOrder
import com.qkt.broker.mt5.MT5Position
import com.qkt.broker.mt5.MT5Symbol
import com.qkt.broker.mt5.MT5Tick
import com.qkt.broker.mt5.MT5Translation
import com.qkt.candles.TimeWindow
import com.qkt.cli.Config
import com.qkt.common.TimeRange
import com.qkt.instrument.QuoteCurrencyGuard
import com.qkt.marketdata.Candle
import com.qkt.marketdata.live.mt5.Mt5BarFetcher
import java.math.BigDecimal
import java.time.Instant

/**
 * One-shot venue access for `qkt bot` commands: synchronous MT5 gateway calls with no
 * event bus and no pollers. Order shapes reuse [MT5OrderTranslator] (the same
 * translation boundary the live broker uses); single-exit SL/TP prices are attached
 * onto the translated wire request, which MT5 accepts on market and pending orders.
 */
class BotGateway(
    val profile: MT5BrokerProfile,
    private val client: MT5Client,
) {
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol)

    /** Broker-side symbol for a `BROKER:SYMBOL` qkt symbol, via the profile's symbol policy. */
    fun brokerSymbol(qktSymbol: String): String = mt5Symbol.toBroker(qktSymbol.substringAfter(':'))

    /**
     * Live facts a compile needs — quote, account value, instrument constraints.
     * Fails when the venue does not return a quote; account/instrument reads that
     * fail leave their fields null so only the sizing forms that need them reject.
     */
    fun quoteContext(
        qktSymbol: String,
        configAccountCurrency: String,
    ): BotQuoteContext {
        val broker = brokerSymbol(qktSymbol)
        val tick =
            client.getTick(broker)
                ?: error("venue did not return a quote for $qktSymbol ($broker); is the gateway up?")
        require(tick.bid.signum() > 0 && tick.ask.signum() > 0) {
            "venue quote for $qktSymbol is not positive: bid=${tick.bid} ask=${tick.ask}"
        }
        val info = client.getSymbolInfo(broker)
        val account = client.getAccount()
        return BotQuoteContext(
            bid = tick.bid,
            ask = tick.ask,
            equity = account?.equity,
            balance = account?.balance,
            contractSize = info?.contractSize,
            accountCurrency = account?.currency?.ifBlank { null } ?: configAccountCurrency,
            quoteCurrency = QuoteCurrencyGuard.quoteOf(qktSymbol),
            volumeMin = info?.volumeMin,
            volumeStep = info?.volumeStep,
            volumeMax = null,
            digits = info?.digits,
        )
    }

    /** Submits a compiled order synchronously and returns the venue's ack or rejection. */
    fun place(order: CompiledBotOrder): BotPlaceResult {
        val wire =
            when (val t = translator.translate(order.request)) {
                is MT5Translation.Single -> t.request
                is MT5Translation.Composite ->
                    error("composite orders are not supported one-shot")
            }
        val attached = wire.copy(sl = wire.sl ?: order.stopLoss, tp = wire.tp ?: order.takeProfit)
        return client.placeOrder(attached).toBotResult()
    }

    /** Closes an open position by ticket; [volume] set closes only that many lots. */
    fun close(
        ticket: Long,
        volume: BigDecimal? = null,
    ): BotPlaceResult = client.closePosition(ticket, volume).toBotResult()

    /** Moves an open position's SL/TP. Pass both — the venue clears an omitted level. */
    fun modify(
        ticket: Long,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): BotPlaceResult = client.modifyPosition(ticket, sl, tp).toBotResult()

    /** Cancels a pending order by ticket. Returns false when the venue refused. */
    fun cancel(ticket: Long): Boolean = client.cancelOrder(ticket).isNotEmpty()

    /** Venue account snapshot, or null when the read failed (unknown, never zero). */
    fun account(): MT5AccountInfo? = client.getAccount()

    /** All open venue positions (including deployed strategies'), or null on read failure. */
    fun positions(): List<MT5Position>? = client.getPositions()

    /** Resting pending orders at the venue, or null on read failure. */
    fun pendingOrders(): List<MT5PendingOrder>? = client.getPendingOrders()

    /** Executed deals in `[fromMs, toMs]` UTC, or null on read failure. */
    fun deals(
        fromMs: Long,
        toMs: Long,
    ): List<MT5Deal>? = client.getDeals(fromMs, toMs)

    /** Current bid/ask for a qkt symbol, or null on read failure. */
    fun tick(qktSymbol: String): MT5Tick? = client.getTick(brokerSymbol(qktSymbol))

    /** The most recent [count] closed bars for [window], fetched from the gateway. */
    fun bars(
        qktSymbol: String,
        window: TimeWindow,
        count: Int,
        nowMs: Long,
    ): List<Candle> {
        val fetcher =
            Mt5BarFetcher(
                baseUrl = profile.gatewayUrl,
                serverTimeZone = profile.serverTimeZone,
                apiKey = profile.apiKey,
            )
        // Over-fetch 3x to survive weekends/closed sessions, then keep the newest bars.
        val fromMs = nowMs - window.durationMs * count * 3
        return fetcher
            .fetchRange(
                symbol = brokerSymbol(qktSymbol),
                window = window,
                range = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(nowMs)),
            ).toList()
            .takeLast(count)
    }

    companion object {
        /**
         * Resolves the broker prefix of [qktSymbol] against the config's MT5 profiles and
         * returns a connected gateway. Fails closed on an unknown prefix — a bot order must
         * never guess its venue.
         */
        fun forSymbol(
            cfg: Config,
            qktSymbol: String,
        ): BotGateway {
            val brokerKey = qktSymbol.substringBefore(':', "")
            require(brokerKey.isNotBlank() && qktSymbol.contains(':')) {
                "symbol must be BROKER:SYMBOL (e.g. EXNESS:XAUUSD), got '$qktSymbol'"
            }
            val profiles =
                MT5BrokerProfileLoader().load(
                    raw = cfg.brokers,
                    defaults = MT5DefaultProfiles.all,
                    env = System.getenv(),
                    calendars = cfg.brokerCalendars,
                    aliases = cfg.brokerAliases,
                    capabilityRestrictions = cfg.brokerCapabilityRestrictions,
                    instrumentOverrides = cfg.brokerInstrumentOverrides,
                )
            val profile =
                profiles.firstOrNull { it.name.equals(brokerKey, ignoreCase = true) }
                    ?: error(
                        "no mt5 broker '$brokerKey' in config; configured: " +
                            (profiles.map { it.name }.ifEmpty { listOf("none") }).joinToString(),
                    )
            val client =
                MT5Client(
                    gatewayUrl = profile.gatewayUrl,
                    serverTimeZone = profile.serverTimeZone,
                    httpTimeoutMs = profile.httpTimeoutMs,
                    retryAttempts = 0,
                    apiKey = profile.apiKey,
                )
            return BotGateway(profile, client)
        }
    }
}
