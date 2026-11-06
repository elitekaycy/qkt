package com.qkt.instrument

import java.math.BigDecimal
import java.time.Instant

/**
 * A tiny event family, `KALSHI:KXT`: two events (E1 expiring 2024-03-04 12:00Z, E2 at 18:00Z) of three strikes each
 * (`greater` than 100, 200, 300), every contract opening at 08:00Z and charged a 7% probability fee rounded to cents.
 */
internal object EventFamilyFixture {
    const val ROOT = "KALSHI:KXT"
    val OPEN_MS = ms("2024-03-04T08:00:00Z")
    val E1_EXPIRY_MS = ms("2024-03-04T12:00:00Z")
    val E2_EXPIRY_MS = ms("2024-03-04T18:00:00Z")
    val FEE = CatalogFee(taker = "0.07", rounding = "0.01")
    val FAMILY = EventFamily(ROOT, "USD", BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE)

    fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** Contract [strike] (1..3) of event [event] (1..2), resolved at [outcome] when given. */
    fun row(
        event: Int,
        strike: Int,
        outcome: String? = null,
    ): ListedEvent {
        val expiry = if (event == 1) E1_EXPIRY_MS else E2_EXPIRY_MS
        return ListedEvent(
            code = "KXT-E$event-T$strike",
            event = "KXT-E$event",
            predicate = CatalogStrike("greater", floor = "${strike * 100}"),
            openAtMs = OPEN_MS,
            closeAtMs = expiry,
            expiryMs = expiry,
            title = "above ${strike * 100}",
            fee = FEE,
            outcome = outcome,
        )
    }

    /** The whole family; [outcomes] maps a code to its outcome, every other contract unresolved. */
    fun catalog(outcomes: Map<String, String> = emptyMap()): EventCatalog =
        EventCatalog(
            ROOT,
            (1..2).flatMap { e -> (1..3).map { s -> row(e, s).let { it.copy(outcome = outcomes[it.code]) } } },
        )
}
