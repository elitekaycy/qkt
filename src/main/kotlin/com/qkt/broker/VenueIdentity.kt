package com.qkt.broker

import java.math.BigDecimal

/**
 * Which connector and account a venue fact came from, stated by the connector so a consumer never
 * infers it from MT5 conventions. [connector] is `mt5`, `vgp` or `paper`; [adapter] the venue behind it
 * (`exness`, `deribit`, `paper`); [label] the config entry name uppercased (`EXNESS`, `PAPER_DERIBIT`);
 * [accountId] an opaque id unique per venue account (`mt5:436804390@Exness-MT5Trial9`,
 * `vgp:deribit:g-aVrEeP`), null until the connector has read it.
 */
data class VenueIdentity(
    val connector: String,
    val adapter: String,
    val label: String,
    val accountId: String?,
)

/**
 * What a venue states about one instrument: its own code for it, its kind (`cfd`, `spot`, `perpetual`,
 * `future`, `option`), and the contract size, price tick and quote currency it lists. Null fields are
 * facts the venue does not state.
 */
data class VenueInstrument(
    val venueCode: String,
    val kind: String?,
    val contractSize: BigDecimal? = null,
    val tickSize: BigDecimal? = null,
    val quoteCurrency: String? = null,
)

/**
 * Opt-in capability of a broker that can describe itself and its instruments. Kept apart from [Broker]
 * so brokers compiled against an earlier qkt keep working. Every method answers from memory: callers
 * include the engine thread.
 */
interface VenueDescribed {
    /** The connector, venue and account this broker trades, or null when not known yet. */
    fun venueIdentity(): VenueIdentity?

    /**
     * The venue position a fill acknowledged as [brokerOrderId] belongs to, when the venue names one
     * (an MT5 fill names its position ticket). Null: the venue names none and qkt mints the id.
     */
    fun fillPositionId(brokerOrderId: String?): String? = null

    /** What the venue states about [qktSymbol], or null when it states nothing. */
    fun instrument(qktSymbol: String): VenueInstrument? = null
}
