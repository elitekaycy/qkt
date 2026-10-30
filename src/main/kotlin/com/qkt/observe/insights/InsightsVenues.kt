package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.VenueDescribed
import com.qkt.broker.VenueIdentity
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * The venue facts every venue envelope carries, stated by the connectors ([VenueDescribed]) and qkt's
 * instrument registry, so the collector never infers them from MT5 conventions: which connector, venue
 * and account an envelope is about, and what one instrument is (kind, contract size, tick, value of a
 * point in account currency). Answers from memory on any thread. [brokers] are the session's venue
 * brokers, asked at call time.
 */
class InsightsVenues(
    private val brokers: () -> List<Broker> = { emptyList() },
    private val instruments: InstrumentRegistry? = null,
) {
    private val accountCurrency = ConcurrentHashMap<String, String>()
    private val rates = ConcurrentHashMap<String, BigDecimal>()
    private val natives = ConcurrentHashMap<String, String>()

    /** The broker behind label [label] (`EXNESS`, `PAPER_DERIBIT`; any case), or null. */
    fun describedBy(label: String): VenueDescribed? =
        brokers().filterIsInstance<VenueDescribed>().firstOrNull { it.venueIdentity()?.label.equals(label, true) }

    /** The broker trading [symbol], by its `LABEL:` prefix, or null. */
    fun describedFor(symbol: String): VenueDescribed? =
        symbol.substringBefore(':', "").takeIf { it.isNotEmpty() }?.let(::describedBy)

    /** `venue` and `accountId` for an envelope about [label]'s account, or none when it states no identity. */
    fun fields(label: String): Map<String, Any?> = venuePayload(describedBy(label)?.venueIdentity())

    /** [label]'s account is held in [currency]: a point of an instrument quoted in it is worth its contract size. */
    fun accountCurrency(
        label: String,
        currency: String,
    ) {
        accountCurrency[label.uppercase()] = currency
    }

    /**
     * qkt's accounting booked a [symbol] fill whose P&L is in [native] and was converted to [account] at
     * [rate] (one native unit is [rate] account units; no rate when the two are one currency).
     */
    fun converted(
        symbol: String,
        native: String,
        account: String,
        rate: BigDecimal?,
    ) {
        natives[symbol] = native
        val effective = if (native == account) BigDecimal.ONE else rate
        if (effective != null && effective.signum() > 0) rates[symbol] = effective
    }

    /** Account-currency value of one unit of [symbol]'s quantity moving one price unit, or null when unknown. */
    fun valuePerPoint(symbol: String): BigDecimal? {
        val facts = instrument(symbol) ?: return null
        val size = facts["contractSize"] as? BigDecimal ?: return null
        val quote = facts["quoteCurrency"] as? String
        val held = accountCurrency[symbol.substringBefore(':').uppercase()]
        val rate = rates[symbol] ?: BigDecimal.ONE.takeIf { quote != null && quote == held } ?: return null
        return size.multiply(rate)
    }

    /** [symbol]'s price grid as qkt's own orders use it (its registry's price step), or null when it has none. */
    fun priceStep(symbol: String): BigDecimal? = instruments?.lookup(symbol)?.priceStep

    /**
     * The `instrument` object for [symbol]: `{symbol, venueCode, kind, quantityUnit, category, contractSize, tickSize,
     * quoteCurrency}` from what qkt's registry and the venue state, or null when neither knows it, plus a
     * derivative's terms: `root`, `expiry` (UTC ms; none for a perpetual), and an option's `strike`,
     * `optionRight` (`call`, `put`) and `underlying`. `category` is the venue's folder for the symbol
     * (`Stocks` for an MT5 stock CFD). qkt's registry wins where both state a value: it is what qkt's own
     * accounting used.
     */
    fun instrument(symbol: String): Map<String, Any?>? {
        val meta = instruments?.lookup(symbol)
        val venue = describedFor(symbol)?.instrument(symbol)
        if (meta == null && venue == null) return null
        val terms = meta?.derivative
        val kind =
            when (terms) {
                is FutureTerms -> if (terms.perpetual) "perpetual" else "future"
                is OptionTerms -> "option"
                else -> venue?.kind
            }
        val option = terms as? OptionTerms
        return mapOf(
            "symbol" to symbol,
            "venueCode" to venue?.venueCode,
            "kind" to kind,
            "quantityUnit" to quantityUnit(kind, venue),
            "category" to meta?.category,
            "contractSize" to (meta?.contractSize ?: venue?.contractSize),
            "tickSize" to (meta?.tickSize ?: venue?.tickSize ?: meta?.pointSize),
            "quoteCurrency" to (meta?.currency ?: venue?.quoteCurrency ?: natives[symbol]),
            "root" to terms?.root,
            "expiry" to (option?.expiryMs ?: (terms as? FutureTerms)?.expiryMs),
            "strike" to option?.strike,
            "optionRight" to option?.right?.name?.lowercase(),
            "underlying" to option?.underlyingIndex,
        )
    }

    /** [instrument] with `valuePerPoint` added when it is known. */
    fun instrumentFields(symbol: String): Map<String, Any?>? =
        instrument(symbol)?.let { it + ("valuePerPoint" to valuePerPoint(symbol)) }
}

/** What one unit of a position's quantity is, on an instrument of [kind]: a coin pair's is the coin. */
private fun quantityUnit(
    kind: String?,
    venue: com.qkt.broker.VenueInstrument?,
): String? =
    when (kind) {
        "cfd" -> "lots"
        "equity" -> "shares"
        "perpetual", "future", "option" -> "contracts"
        "spot" -> venue?.baseAsset
        else -> null
    }

/**
 * `venue: {connector, adapter, label, tradeMode}` and `accountId` for an envelope about [identity]'s account,
 * the same two fields on every venue envelope of every connector; empty when the broker states no identity.
 */
fun venuePayload(identity: VenueIdentity?): Map<String, Any?> =
    if (identity == null) {
        emptyMap()
    } else {
        mapOf(
            "venue" to
                mapOf(
                    "connector" to identity.connector,
                    "adapter" to identity.adapter,
                    "label" to identity.label,
                    "tradeMode" to identity.tradeMode,
                ),
            "accountId" to identity.accountId,
        )
    }
