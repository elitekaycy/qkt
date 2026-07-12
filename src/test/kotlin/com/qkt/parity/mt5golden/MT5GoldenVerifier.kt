package com.qkt.parity.mt5golden

import com.qkt.broker.MT5BrokerSimulator
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.RoundingMode

internal data class MT5GoldenVerification(
    val submissionsCompared: Int,
    val dealGroupsCompared: Int,
    val capturedVenueCost: BigDecimal,
)

internal object MT5GoldenVerifier {
    fun verify(
        fixture: MT5GoldenFixture,
        requireAuthentic: Boolean = true,
    ): MT5GoldenVerification {
        fixture.validate(requireAuthentic)
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val prices = MarketPriceTracker()
        val meta = fixture.instrument.toMeta()
        val registry =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String): InstrumentMeta? = meta.takeIf { it.qktSymbol == qktSymbol }
            }
        val fills = mutableListOf<FillSlice>()
        val acknowledgements = linkedMapOf<String, Boolean>()
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { event ->
            fills += FillSlice(event.clientOrderId, event.price, event.quantity)
        }
        bus.subscribe<BrokerEvent.OrderFilled> { event ->
            fills += FillSlice(event.clientOrderId, event.price, event.quantity)
        }
        val simulator =
            MT5BrokerSimulator(
                bus = bus,
                clock = clock,
                priceProvider = prices,
                instruments = registry,
                syntheticSpreadPoints = 0,
                enforceStopsLevel = true,
            )

        val timeline =
            (
                fixture.ticks.map { Timeline.TickItem(it.sequence, it) } +
                    fixture.submissions.map { Timeline.SubmissionItem(it.sequence, it) }
            ).sortedBy { it.sequence }
        timeline.forEach { item ->
            when (item) {
                is Timeline.TickItem -> {
                    val tick = item.tick.toTick()
                    clock.advanceTo(tick.timestamp)
                    prices.update(tick.symbol, tick.price)
                    bus.publish(TickEvent(tick))
                }
                is Timeline.SubmissionItem -> {
                    val order = item.submission.clientOrder.toOrderRequest()
                    clock.advanceTo(order.timestamp)
                    acknowledgements[order.id] = simulator.submit(order).accepted
                }
            }
        }

        fixture.venueOrders.forEach { expected ->
            val actual = acknowledgements[expected.clientOrderId]
            require(actual == expected.accepted) {
                "${expected.clientOrderId}: simulator accepted=$actual, venue accepted=${expected.accepted} " +
                    "(retcode=${expected.retcode})"
            }
        }

        val simulated = fills.groupBy(FillSlice::clientOrderId).mapValues { aggregate(it.value) }
        val venue =
            fixture.venueDeals
                .groupBy(CapturedVenueDeal::clientOrderId)
                .mapValues { (_, deals) ->
                    aggregate(deals.map { FillSlice(it.clientOrderId, decimal(it.price), decimal(it.executedVolume)) })
                }
        require(simulated.keys == venue.keys) {
            "simulated deal groups ${simulated.keys} differ from venue deal groups ${venue.keys}"
        }
        val priceTolerance = meta.pointSize.multiply(BigDecimal(fixture.tolerances.pricePoints))
        val volumeTolerance = meta.volumeStep.multiply(BigDecimal(fixture.tolerances.volumeSteps))
        venue.forEach { (clientOrderId, expected) ->
            val actual = simulated.getValue(clientOrderId)
            require(actual.volume.subtract(expected.volume).abs() <= volumeTolerance) {
                "$clientOrderId: simulated volume ${actual.volume} differs from venue ${expected.volume} " +
                    "by more than $volumeTolerance"
            }
            require(actual.averagePrice.subtract(expected.averagePrice).abs() <= priceTolerance) {
                "$clientOrderId: simulated price ${actual.averagePrice} differs from venue ${expected.averagePrice} " +
                    "by more than $priceTolerance"
            }
        }
        val venueCost =
            fixture.venueDeals.fold(BigDecimal.ZERO) { total, deal ->
                total + decimal(deal.commission) + decimal(deal.swap) + decimal(deal.fee)
            }
        return MT5GoldenVerification(
            submissionsCompared = fixture.submissions.size,
            dealGroupsCompared = venue.size,
            capturedVenueCost = venueCost,
        )
    }

    private sealed interface Timeline {
        val sequence: Long

        data class TickItem(
            override val sequence: Long,
            val tick: CapturedTick,
        ) : Timeline

        data class SubmissionItem(
            override val sequence: Long,
            val submission: CapturedSubmission,
        ) : Timeline
    }

    private data class FillSlice(
        val clientOrderId: String,
        val price: BigDecimal,
        val volume: BigDecimal,
    )

    private data class AggregateFill(
        val volume: BigDecimal,
        val averagePrice: BigDecimal,
    )

    private fun aggregate(fills: List<FillSlice>): AggregateFill {
        val volume = fills.fold(BigDecimal.ZERO) { total, fill -> total + fill.volume }
        require(volume.signum() > 0) { "deal group must have positive volume" }
        val notional = fills.fold(BigDecimal.ZERO) { total, fill -> total + fill.price.multiply(fill.volume) }
        return AggregateFill(volume, notional.divide(volume, 12, RoundingMode.HALF_EVEN))
    }

    private fun CapturedInstrument.toMeta(): InstrumentMeta =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = decimal(contractSize),
            volumeStep = decimal(volumeStep),
            volumeMin = decimal(volumeMin),
            volumeMax = volumeMax?.let(::decimal),
            pointSize = decimal(pointSize),
            digits = digits,
            tradeStopsLevelPoints = tradeStopsLevelPoints,
        )

    private fun CapturedTick.toTick(): Tick {
        val bidValue = decimal(bid)
        val askValue = decimal(ask)
        return Tick(
            symbol = symbol,
            price = bidValue.add(askValue).divide(BigDecimal(2)),
            timestamp = timestampMs,
            bid = bidValue,
            ask = askValue,
        )
    }

    private fun CapturedClientOrder.toOrderRequest(): OrderRequest {
        val parsedSide = Side.valueOf(side)
        val parsedTif = TimeInForce.valueOf(timeInForce)
        val parsedQuantity = decimal(quantity)
        return when (kind) {
            CapturedOrderKind.MARKET ->
                OrderRequest.Market(id, symbol, parsedSide, parsedQuantity, parsedTif, timestampMs, strategyId)
            CapturedOrderKind.LIMIT ->
                OrderRequest.Limit(
                    id,
                    symbol,
                    parsedSide,
                    parsedQuantity,
                    decimal(requireNotNull(limitPrice)),
                    parsedTif,
                    timestampMs,
                    strategyId,
                    expiresAtMs,
                )
            CapturedOrderKind.STOP ->
                OrderRequest.Stop(
                    id,
                    symbol,
                    parsedSide,
                    parsedQuantity,
                    decimal(requireNotNull(stopPrice)),
                    parsedTif,
                    timestampMs,
                    strategyId,
                    expiresAtMs,
                )
            CapturedOrderKind.STOP_LIMIT ->
                OrderRequest.StopLimit(
                    id,
                    symbol,
                    parsedSide,
                    parsedQuantity,
                    decimal(requireNotNull(stopPrice)),
                    decimal(requireNotNull(limitPrice)),
                    parsedTif,
                    timestampMs,
                    strategyId,
                    expiresAtMs,
                )
        }
    }
}
