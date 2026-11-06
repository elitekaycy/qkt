package com.qkt.instrument

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant

/**
 * An hourly ladder family, `KALSHI:KXS`: three events (H1, H2, H3 closing and expiring 2024-03-04 at 11:00, 12:00 and
 * 13:00 UTC), each of four `greater` strikes (100, 200, 300, 400), every contract listed from 09:00 and charged a 7%
 * probability fee rounded up to the cent. Contract `KXS-H<e>-T<s>` trades as `KALSHI:KXS_H<e>_T<s>`.
 */
internal object SlotFamilyFixture {
    const val ROOT = "KALSHI:KXS"
    val FAMILY = EventFamily(ROOT, "USD", BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE)
    val OPEN_MS = ms("2024-03-04T09:00:00Z")

    fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** Event [event]'s close and expiry: 10:00 plus [event] hours. */
    fun closeMs(event: Int): Long = ms("2024-03-04T10:00:00Z") + event * 3_600_000L

    /** The qkt symbol of strike [strike] of event [event]. */
    fun symbol(
        event: Int,
        strike: Int,
    ): String = "${ROOT}_H${event}_T$strike"

    fun row(
        event: Int,
        strike: Int,
        outcome: String? = null,
        exclusive: Boolean = false,
        predicate: CatalogStrike = CatalogStrike("greater", floor = "${strike * 100}"),
    ): ListedEvent =
        ListedEvent(
            code = "KXS-H$event-T$strike",
            event = "KXS-H$event",
            predicate = predicate,
            openAtMs = OPEN_MS,
            closeAtMs = closeMs(event),
            expiryMs = closeMs(event),
            exclusive = exclusive,
            fee = CatalogFee(taker = "0.07", rounding = "0.01"),
            outcome = outcome,
        )

    /** The whole family; [outcomes] maps a code to its outcome, every other contract resolving at 0. */
    fun catalog(outcomes: Map<String, String> = emptyMap()): EventCatalog =
        EventCatalog(
            ROOT,
            (1..3).flatMap { e -> (1..4).map { s -> row(e, s).let { it.copy(outcome = outcomes[it.code] ?: "0") } } },
        )

    fun registry(catalog: EventCatalog = catalog()): EventCatalogRegistry =
        EventCatalogRegistry(listOf(FAMILY), mapOf(ROOT to catalog))

    /** A quote tick of [symbol] at [atMs]: [bid]/[ask], either absent when null. */
    fun quote(
        symbol: String,
        atMs: Long,
        bid: String?,
        ask: String?,
    ): Tick {
        val price = BigDecimal(ask ?: bid ?: "0")
        return Tick(symbol, price, atMs, bid = bid?.let(::BigDecimal), ask = ask?.let(::BigDecimal))
    }
}
