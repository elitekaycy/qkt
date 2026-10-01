package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * One account's connection to its VGP v1 gateway, shared by every strategy trading on the account and
 * open for as long as the account is: one client, one event stream, one translator. Each strategy's
 * broker attaches with the strategy it serves and its session's positions; [GatewayRouting] sends each
 * event where it belongs. The gateway's identity is checked when the stream opens and whenever its log
 * restarts; once every strategy of [expectedStrategies] is ready, their holdings must add up to the
 * account's ([GatewayHolders]). Either failure stops new risk: an identity mismatch refuses every order,
 * a holdings mismatch every order that is not reduce-only.
 */
internal class GatewaySession(
    val client: GatewayClient,
    val symbols: GatewaySymbols,
    private val clock: Clock,
    private val identity: GatewayIdentity,
    private val expectedStrategies: Set<String>,
    streamFactory: (
        onEvent: (WireEvent) -> Unit,
        onReset: (String) -> Unit,
        onConnection: (Boolean, String) -> Unit,
    ) -> GatewayStream,
    recoveryWindowMs: Long = 5 * 60_000L,
    submitDeadlineMs: Long = 30_000L,
    retryMs: Long = 1_000L,
    resyncRetryMs: Long = 5_000L,
) {
    private val log = LoggerFactory.getLogger(GatewaySession::class.java)
    private val lock = Any()

    /** What the account last reported. */
    val account = GatewayAccountState()
    private val routing = GatewayRouting()
    private val ledger = GatewayLedger(symbols, routing, lock)
    private val holders = GatewayHolders(symbols)
    private val placement = GatewayPlacement(client, clock, ledger::onOrder, ledger::onFill, submitDeadlineMs, retryMs)
    private val sync =
        GatewaySync(
            client,
            ledger::onOrder,
            ledger::onFill,
            ledger::onSettlement,
            ledger::owns,
            clock.now() - recoveryWindowMs,
            clock::now,
        )
    private val resyncer =
        GatewayResyncer(
            clock,
            ::reconcile,
            { identity.mismatch(client.health())?.let(::refuse) },
            ::alertUnreachable,
            resyncRetryMs,
        )
    private val decoder =
        GatewayEventDecoder(
            ledger::onOrder,
            ledger::onFill,
            ledger::onSettlement,
            account::position,
            account::account,
        )
    private val stream = streamFactory(::onEvent, { reason -> resyncer.resync("stream $reason") }, ::onConnection)
    private var started = false

    /** Why no order may be sent (wrong gateway), or null. */
    @Volatile var refused: String? = null
        private set

    /** Why no order that adds risk may be sent (holdings disagree with the account), or null. */
    @Volatile var riskRefused: String? = null
        private set

    /** Attaches a broker for [strategy] (null: every strategy); the first one opens the stream. Throws when the gateway cannot answer. */
    fun attach(
        strategy: String?,
        positions: PositionProvider,
        publish: (BrokerEvent) -> Unit,
    ): GatewayRouting.Attached =
        synchronized(lock) {
            val broker = GatewayRouting.Attached(strategy, positions, publish)
            routing.attach(broker)
            if (!started) {
                try {
                    val health = client.health()
                    identity.mismatch(health)?.let { error(it) }
                    reconcile("start")
                    stream.anchor(health.stream, health.seq)
                    stream.start()
                    started = true
                } catch (e: RuntimeException) {
                    routing.detach(broker)
                    throw e
                }
            }
            broker
        }

    /** [broker]'s session is restored: settle the contracts it still holds that expired while away, then check holdings. */
    fun ready(broker: GatewayRouting.Attached) {
        val held = broker.positions.symbols().mapNotNull(symbols::venue)
        GatewayRecovery.settleHeld(client, held) { settlement -> ledger.settleFor(broker, settlement) }
        synchronized(lock) {
            holders.ready(broker)
            holders.mismatch(routing.brokers, expectedStrategies, account.positions)?.let { reason ->
                log.error("gateway account check failed: {}", reason)
                riskRefused = reason
            }
        }
    }

    /** Detaches [broker]; the connection stays open with the account. */
    fun detach(broker: GatewayRouting.Attached) = synchronized(lock) { routing.detach(broker) }

    /** Sends [body] for [strategy]; [reject] reports a refusal on the sender's bus. */
    fun submit(
        strategy: String,
        body: WireSubmit,
        reject: (String) -> Unit,
    ) {
        val blocked = refused ?: riskRefused?.takeUnless { body.reduceOnly }
        if (blocked != null) return reject(blocked)
        ledger.own(body.clientOrderId, strategy, BigDecimal(body.quantity))
        placement.submit(body, reject)
    }

    /** Cancels [clientOrderId]; the order's end arrives as its events. */
    fun cancel(clientOrderId: String) = placement.cancel(clientOrderId)

    /** Takes back orders a restart restored; returns the ids the gateway knows. Throws when it cannot answer. */
    fun recover(orders: List<RecoveredOrder>): Set<String> {
        orders.forEach { ledger.own(it.clientOrderId, it.strategyId, it.quantity, it.alreadyFilled) }
        return GatewayRecovery.recover(client, orders, ledger::markBooked, ledger::onFill, ledger::onOrder)
    }

    /** Refreshes the gateway's listing off the caller's thread. */
    fun refreshListing() = placement.background { symbols.update(client.instruments().map { it.code }) }

    /** Closes the connection with the account. */
    fun close() {
        stream.stop()
        placement.shutdown()
    }

    private fun reconcile(reason: String) {
        log.info("gateway resync: {}", reason)
        symbols.update(client.instruments().map { it.code })
        account.apply(sync.run(ledger.openOrders))
    }

    private fun alertUnreachable(failures: Int) =
        synchronized(lock) { routing.broadcast(BrokerEvent.GatewayUnreachable("Gateway", failures, clock.now())) }

    private fun refuse(reason: String) {
        log.error("gateway refused: {}", reason)
        refused = reason
    }

    private fun onEvent(event: WireEvent) {
        resyncer.retryOwed()
        decoder.decode(event)
    }

    private fun onConnection(
        connected: Boolean,
        reason: String,
    ) {
        val state = if (connected) BrokerEvent.ConnectionState.CONNECTED else BrokerEvent.ConnectionState.DISCONNECTED
        synchronized(lock) {
            routing.broadcast(BrokerEvent.ConnectionChanged("Gateway", state, reason, timestamp = clock.now()))
        }
        if (connected) resyncer.retryOwed()
    }
}
