package com.qkt.instrument

import java.math.BigDecimal

/**
 * The static spec shared by every contract of one event family, declared once under `events:` in `instruments.yaml`:
 * prices in [currency] on a [tickSize] grid, volume in [volumeStep] from [volumeMin]. Each contract pays 1, so its
 * `contractSize` is 1; its terms come from the family's event catalog.
 */
data class EventFamily(
    val root: String,
    val currency: String,
    val tickSize: BigDecimal,
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
) {
    init {
        require(ROOT_FORMAT.matches(root)) { "EventFamily.root must be VENUE:FAMILY: '$root'" }
        require(CURRENCY_FORMAT.matches(currency)) { "EventFamily.currency must be a 3-5 letter code: '$currency'" }
        require(tickSize.signum() > 0 && tickSize < BigDecimal.ONE) {
            "EventFamily.tickSize must be in (0, 1): $tickSize"
        }
        require(volumeStep.signum() > 0) { "EventFamily.volumeStep must be > 0: $volumeStep" }
        require(volumeMin >= volumeStep) { "EventFamily.volumeMin must be >= volumeStep: $volumeMin < $volumeStep" }
    }

    /** The venue prefix of [root]: `KALSHI` for `KALSHI:KXINXU`. */
    val venue: String get() = root.substringBefore(':')

    /**
     * Metadata for [qktSymbol], a contract of this family with [terms], or a slot of it ([SlotSymbol]) when [terms] is
     * null: a slot sizes like the family's contracts but is no contract, so it carries no terms and is never traded.
     */
    fun metaFor(
        qktSymbol: String,
        terms: EventTerms?,
    ): InstrumentMeta =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = BigDecimal.ONE,
            volumeStep = volumeStep,
            volumeMin = volumeMin,
            volumeMax = null,
            pointSize = tickSize,
            digits = tickSize.stripTrailingZeros().scale().coerceAtLeast(0),
            tradeStopsLevelPoints = 0,
            currency = currency,
            derivative = terms,
        )

    private companion object {
        val ROOT_FORMAT = Regex("[A-Z0-9_]+:[A-Za-z0-9_]+")
        val CURRENCY_FORMAT = Regex("[A-Z]{3,5}")
    }
}
