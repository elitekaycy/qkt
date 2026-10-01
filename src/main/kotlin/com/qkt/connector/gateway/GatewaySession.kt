package com.qkt.connector.gateway

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * One account's connection to its VGP v1 gateway, shared by every strategy trading on the account:
 * one client, one event stream, one translator. Each strategy's broker attaches with the strategy it
 * serves (null: every strategy of a multi-strategy session) and the bus it publishes on. An order's
 * events go to the broker of the strategy that sent it; a contract [ContractSettled] goes to every
 * broker, since each strategy settles its own holding. The stream opens with the first broker and
 * closes with the last.
 */
internal class GatewaySession(
    val client: GatewayClient,
    val symbols: GatewaySymbols,
    private val clock: Clock,
    streamFactory: (
        onEvent: (WireEvent) -> Unit,
        onReset: (String) -> Unit,
        onConnection: (Boolean, String) -> Unit,
    ) -> GatewayStream,
    recoveryWindowMs: Long = 5 * 60_000L,
) {
    private val log = LoggerFactory.getLogger(GatewaySession::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val owners = ConcurrentHashMap<String, String>()
    private val translator = GatewayEventTranslator(symbols) { id -> owners[id] ?: "" }
    private val routing = GatewayRouting()
    private val unconfirmed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val settled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val placement = GatewayPlacement(client, ::onOrder) { id -> unconfirmed += id }
    private val sync = GatewaySync(client, ::onOrder, ::onFill, ::settle, clock.now() - recoveryWindowMs, clock::now)
    private val stream = streamFactory(::onEvent, { reason -> resync("stream $reason") }, ::onConnection)

    @Volatile var equity: BigDecimal? = null
        private set

    @Volatile var accounting = PositionAccountingMode.UNKNOWN
        private set

    @Volatile private var owedResync: String? = null

    /** Attaches a broker for [strategy] publishing on [publish]; the first one reconciles and opens the stream. */
    fun attach(
        strategy: String?,
        publish: (BrokerEvent) -> Unit,
    ): AutoCloseable =
        synchronized(lock) {
            val (first, detach) = routing.attach(strategy, publish)
            // The gateway must answer at start: trading on unknown venue state is refused.
            if (first) {
                val health = client.health()
                reconcile("start")
                stream.anchor(health.stream, health.seq)
                stream.start()
            }
            AutoCloseable {
                synchronized(lock) {
                    if (detach()) {
                        stream.stop()
                        placement.shutdown()
                    }
                }
            }
        }

    /** Sends [body] for [strategy] off the caller's thread; [reject] reports a refusal on the sender's bus. */
    fun submit(
        strategy: String,
        body: WireSubmit,
        reject: (String) -> Unit,
    ) {
        owners[body.clientOrderId] = strategy
        synchronized(lock) { translator.expect(body.clientOrderId, BigDecimal(body.quantity)) }
        placement.submit(body, reject)
    }

    /**
     * Takes back orders a restart restored: [orders] are owned again and reconciled, so their fills
     * while qkt was down are booked once. Returns the ids the gateway knows (working, or filled in the
     * deals); throws when the gateway cannot answer.
     */
    fun recover(orders: List<RecoveredOrder>): Set<String> {
        synchronized(lock) {
            for (order in orders) {
                owners[order.clientOrderId] = order.strategyId
                translator.expect(order.clientOrderId, order.quantity, order.alreadyFilled)
            }
        }
        val asked = orders.map { it.clientOrderId }.toSet()
        return asked intersect reconcileState("recover", asked).found
    }

    /** Cancels [clientOrderId] off the caller's thread; the order's end arrives as its events. */
    fun cancel(clientOrderId: String) = placement.cancel(clientOrderId)

    private fun resync(reason: String) {
        try {
            reconcile(reason)
            owedResync = null
        } catch (e: GatewayUnavailableException) {
            log.warn("gateway resync ({}) owed: {}", reason, e.message)
            owedResync = reason
        }
    }

    private fun reconcile(reason: String) {
        reconcileState(reason)
    }

    /** Reconciles, asking also about [asked]; only unconfirmed submits the gateway never placed are rejected. */
    private fun reconcileState(
        reason: String,
        asked: Set<String> = emptySet(),
    ): GatewaySyncState {
        log.info("gateway resync: {}", reason)
        val state = sync.run(unconfirmed.toSet() + asked)
        equity = state.equity
        accounting = state.accounting
        for (id in state.neverPlaced.filter { it in unconfirmed }) {
            unconfirmed -= id
            routing.route(
                BrokerEvent.OrderRejected(
                    id,
                    null,
                    "not placed: the gateway was unreachable when it was sent",
                    owners[id] ?: "",
                ),
            )
        }
        unconfirmed.removeAll(state.found)
        return state
    }

    private fun onEvent(event: WireEvent) {
        owedResync?.let { resync("owed: $it") }
        val data = requireNotNull(event.data) { "${event.type} event without data" }
        when (event.type) {
            "order" -> onOrder(json.decodeFromJsonElement(WireOrder.serializer(), data))
            "fill" -> onFill(json.decodeFromJsonElement(WireFill.serializer(), data))
            "settlement" -> settle(json.decodeFromJsonElement(WireSettlement.serializer(), data))
            "account" -> equity = BigDecimal(json.decodeFromJsonElement(WireAccount.serializer(), data).equity)
            "position", "quote", "kill" -> Unit
            else -> throw GatewayProtocolException("event type '${event.type}'")
        }
    }

    private fun onConnection(
        connected: Boolean,
        reason: String,
    ) {
        val state = if (connected) BrokerEvent.ConnectionState.CONNECTED else BrokerEvent.ConnectionState.DISCONNECTED
        routing.broadcast(BrokerEvent.ConnectionChanged("Gateway", state, reason, timestamp = clock.now()))
        if (connected) owedResync?.let { resync("owed: $it") }
    }

    /** An order no strategy here owns (yet: a restart hands them back) is not reported until one does. */
    private fun onOrder(order: WireOrder) {
        if (!owners.containsKey(order.clientOrderId)) return
        synchronized(lock) { translator.order(order) }?.let(routing::route)
    }

    /** A fill of an order no strategy here sent (yet: a restart hands them back) stays unbooked until one does. */
    private fun onFill(fill: WireFill) {
        if (!owners.containsKey(fill.clientOrderId)) return
        synchronized(lock) { translator.fill(fill) }?.let(routing::route)
    }

    /** Broadcasts a settlement once: the stream and a resynchronization may both report it. */
    private fun settle(settlement: WireSettlement) {
        if (!settled.add("${settlement.symbol}@${settlement.time}")) return
        routing.broadcast(synchronized(lock) { translator.settlement(settlement) })
    }
}
