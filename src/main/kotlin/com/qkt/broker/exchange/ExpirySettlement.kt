package com.qkt.broker.exchange

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.deliveryPrice
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * The exchange's side of dated contracts in [ExchangeSimulator]: each strategy's net position per
 * contract, built from the simulator's own fills, and the settlement of those positions once a
 * contract expires. A settlement is published as a venue close ([BrokerEvent.OrderFilled] with
 * `updatesOrderExecution = false` and [ExitReason.EXPIRY]) at the catalog's delivery price, or at
 * the contract's last price, with a warning, when the catalog records none; each is recorded in the
 * run's [SettlementLog].
 */
internal class ExpirySettlement(
    private val bus: EventBus,
    private val clock: Clock,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry,
    private val log: SettlementLog,
) {
    private val logger = LoggerFactory.getLogger(ExpirySettlement::class.java)
    private val pending = HashMap<String, Long>()
    private val expired = HashSet<String>()
    private val net = HashMap<String, LinkedHashMap<String, BigDecimal>>()
    private var nextDueMs = Long.MAX_VALUE

    /** Whether [symbol] has expired and been settled. */
    fun isExpired(symbol: String): Boolean = symbol in expired

    /** Watch [symbol], a dated contract expiring at [expiryMs], for settlement. */
    fun track(
        symbol: String,
        expiryMs: Long,
    ) {
        if (symbol in expired || pending.putIfAbsent(symbol, expiryMs) != null) return
        nextDueMs = minOf(nextDueMs, expiryMs)
    }

    /** [strategyId]'s net position in [symbol] as the exchange holds it, positive long. */
    fun netOf(
        symbol: String,
        strategyId: String,
    ): BigDecimal = net[symbol]?.get(strategyId) ?: BigDecimal.ZERO

    /** Every strategy's non-zero net position in [symbol], positive long, in the order they first traded it. */
    fun holdersOf(symbol: String): Map<String, BigDecimal> = net[symbol]?.filterValues { it.signum() != 0 }.orEmpty()

    /** Apply one of the simulator's fills to its strategy's net position. */
    fun onFill(fill: BrokerEvent.OrderFilled) {
        val signed = if (fill.side == Side.BUY) fill.quantity else fill.quantity.negate()
        val byStrategy = net.getOrPut(fill.symbol) { LinkedHashMap() }
        byStrategy[fill.strategyId] = (byStrategy[fill.strategyId] ?: BigDecimal.ZERO).add(signed)
    }

    /**
     * Settle every watched contract that has expired by [nowMs]: [beforeSettle] runs first for each
     * (the simulator cancels its working orders there), then every non-flat position is closed.
     */
    fun settleDue(
        nowMs: Long,
        beforeSettle: (symbol: String) -> Unit,
    ) {
        if (nowMs < nextDueMs) return
        val due = pending.filterValues { it <= nowMs }.keys.sorted()
        for (symbol in due) {
            pending.remove(symbol)
            expired += symbol
            beforeSettle(symbol)
            settle(symbol)
        }
        nextDueMs = pending.values.minOrNull() ?: Long.MAX_VALUE
    }

    private fun settle(symbol: String) {
        val holders = net.remove(symbol)?.filterValues { it.signum() != 0 } ?: return
        if (holders.isEmpty()) return
        val delivery = instruments.deliveryPrice(symbol)
        val price = (delivery ?: lastPrice(symbol)).setScale(Money.SCALE, Money.ROUNDING)
        for ((strategyId, quantity) in holders) {
            val id = "expiry:$symbol:$strategyId"
            val side = if (quantity.signum() > 0) Side.SELL else Side.BUY
            log.record(Settlement(clock.now(), strategyId, symbol, side, quantity.abs(), price, delivery != null))
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = id,
                    brokerOrderId = id,
                    symbol = symbol,
                    side = side,
                    price = price,
                    quantity = quantity.abs(),
                    strategyId = strategyId,
                    timestamp = clock.now(),
                    updatesOrderExecution = false,
                    exitReason = ExitReason.EXPIRY,
                ),
            )
        }
    }

    private fun lastPrice(symbol: String): BigDecimal {
        val last =
            requireNotNull(prices.lastPrice(symbol)) {
                "cannot settle $symbol at ${Instant.ofEpochMilli(clock.now())}: no delivery price and no last price"
            }
        logger.warn(
            "{} has no delivery price in its catalog; settling at its last price {}",
            symbol,
            last.toPlainString(),
        )
        return last
    }
}
