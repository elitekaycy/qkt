package com.qkt.broker.mt5

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import org.slf4j.LoggerFactory

/**
 * Routes orders to a MetaTrader 5 venue via an `mt5-gateway` HTTP service.
 *
 * Per-instance: each [com.qkt.app.LiveSession] instantiates its own broker so daemon
 * lifecycles are clean. Translates qkt [OrderRequest]s into MT5 wire shapes through
 * [MT5OrderTranslator], polls open positions through [MT5PositionPoller], and recovers
 * state on startup via [MT5StateRecovery].
 *
 * The profile determines venue identity, symbol policy (suffix translation), magic
 * number, and capability restrictions. See [MT5DefaultProfiles] for shipped templates
 * (Exness, ICMarkets, FTMO, Pepperstone) and [MT5BrokerProfileLoader] for YAML config.
 */
class MT5Broker(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val client: MT5Client =
        MT5Client(
            gatewayUrl = profile.gatewayUrl,
            tzOffsetHours = profile.serverTzOffsetHours,
            httpTimeoutMs = profile.httpTimeoutMs,
            retryAttempts = profile.retryAttempts,
        ),
) : Broker {
    override val name: String = profile.name
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities

    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol)
    private val poller = MT5PositionPoller(client, profile, mt5Symbol, bus, clock)
    private val stateRecovery = MT5StateRecovery(client, profile, mt5Symbol, bus)

    init {
        try {
            stateRecovery.recover()
            poller.start()
        } catch (e: Exception) {
            log.warn("MT5Broker ${profile.name} startup degraded: ${e.message}")
        }
    }

    override fun supports(symbol: String): Boolean = true

    override fun submit(request: OrderRequest): SubmitAck {
        if (request !is OrderRequest.Market && request !is OrderRequest.Bracket) {
            return SubmitAck(
                clientOrderId = request.id,
                brokerOrderId = null,
                accepted = false,
                rejectReason =
                    "MT5 v1 does not natively support ${request::class.simpleName}; " +
                        "engine fallback required",
            )
        }
        val mt5Req = translator.translate(request)
        val resp = client.placeOrder(mt5Req)
        if (!isOrderSuccessful(resp.result.retcode)) {
            val reason = resp.errorMessage ?: "retcode=${resp.result.retcode}"
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = request.id,
                    brokerOrderId = null,
                    reason = reason,
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(
                clientOrderId = request.id,
                brokerOrderId = null,
                accepted = false,
                rejectReason = reason,
            )
        }
        val brokerOrderId = resp.result.deal.toString()
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = brokerOrderId,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = brokerOrderId,
                symbol = request.symbol,
                side = request.side,
                price = resp.result.price,
                quantity = request.quantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = brokerOrderId,
            accepted = true,
        )
    }

    override fun cancel(orderId: String) {
        // v1: no native pending orders — nothing to cancel server-side.
    }

    fun shutdown() {
        poller.stop()
    }
}
