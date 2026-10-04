package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.events.FUNDING_REPLAY_MS
import com.qkt.events.FundingCharged
import java.math.BigDecimal

/**
 * A gateway account's perpetual funding. Whether the gateway reports it is its `funding` capability
 * ([declared], from `/v1/health`); without it, an order that could open or add to a perpetual position is
 * refused ([refusal]), since its funding would never be booked. Each `funding` record ([record], or read
 * by a resynchronization through [readWindow]) becomes a [FundingCharged] for every attached broker
 * ([routing], under [lock]), charged on the record's position, or, when the venue gave none, on what the
 * attached sessions hold together, so none books another's share; each session books its strategies' own
 * parts and drops what it already booked. A broker whose session is ready gets the last
 * [FUNDING_REPLAY_MS] of funding again ([replay]), so what was funded while qkt was away is booked.
 */
internal class GatewayFunding(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
    private val clock: Clock,
    private val routing: GatewayRouting,
    private val lock: Any,
) {
    @Volatile private var capabilities: Set<String> = emptySet()

    @Volatile private var perpetuals: Set<String> = emptySet()

    /** Whether the gateway declares that it reports funding. */
    val declared: Boolean get() = FUNDING_CAPABILITY in capabilities

    /** Takes [health]'s capabilities. */
    fun heard(health: WireHealth) {
        capabilities = health.capabilities.toSet()
    }

    /** Takes [listing]'s perpetual codes. */
    fun listed(listing: List<WireInstrument>) {
        perpetuals = listing.filter { it.kind == "perpetual" }.map { it.code }.toSet()
    }

    /** Why [body] may not go (it could add to a perpetual the gateway reports no funding of), or null. */
    fun refusal(body: WireSubmit): String? =
        if (declared || body.reduceOnly || body.symbol !in perpetuals) {
            null
        } else {
            "the gateway does not report funding (capability 'funding'), so ${body.symbol}, a perpetual, " +
                "is not traded: its funding would never be booked"
        }

    /** A `funding` record, handed to every attached broker. */
    fun record(funding: WireFunding) = synchronized(lock) { routing.broadcast(charged(funding)) }

    /** Reads the funding over [window] (from, to) for every attached broker, when the gateway reports it. */
    fun readWindow(window: Pair<Long, Long>) {
        // Never further back than a replay reaches, so no record outlives what sessions remember booking.
        val from = maxOf(window.first, window.second - FUNDING_REPLAY_MS)
        if (declared) client.funding(from, window.second).forEach(::record)
    }

    /** The event a `funding` record means for every session. */
    fun charged(funding: WireFunding): FundingCharged {
        val amount =
            funding.amount.toBigDecimalOrNull() ?: throw GatewayProtocolException("funding amount '${funding.amount}'")
        val basis =
            funding.position?.let {
                it.toBigDecimalOrNull()
                    ?: throw GatewayProtocolException("funding position '$it'")
            }
        return FundingCharged(
            funding.fundingId,
            symbols.qkt(funding.symbol),
            amount,
            funding.currency,
            basis ?: attachedHolding(symbols.qkt(funding.symbol)),
            funding.time,
        )
    }

    /** What every attached session holds of [symbol], signed: who shares a record that names no position. */
    private fun attachedHolding(symbol: String): BigDecimal =
        routing.brokers.fold(BigDecimal.ZERO) { sum, broker -> sum.add(broker.holding(symbol)) }

    /** Hands [broker] the account's funding of the last [FUNDING_REPLAY_MS], when the gateway reports it. */
    fun replay(broker: GatewayRouting.Attached) {
        if (!declared) return
        val now = clock.now()
        client.funding(now - FUNDING_REPLAY_MS, now).forEach { broker.publish(charged(it)) }
    }
}
