package com.qkt.connector.gateway

/**
 * A gateway's `kind: event` listing of the hourly ladder series `KXS`: events `KXS-H<e>` closing (and settling)
 * [OPEN_MS] plus `e` hours, each of `greater` strikes `KXS-H<e>-T<s>` at `s × 100`, listed from [OPEN_MS] in category
 * `Financials` with a 7% probability fee. A contract trades as `KALSHI:KXS_H<e>_T<s>`.
 */
internal object EventWire {
    const val OPEN_MS = 1_794_000_000_000L
    const val ROOT = "KALSHI:KXS"

    fun closeMs(
        event: Int,
        openMs: Long = OPEN_MS,
    ): Long = openMs + event * 3_600_000L

    fun code(
        event: Int,
        strike: Int,
    ): String = "KXS-H$event-T$strike"

    fun symbol(
        event: Int,
        strike: Int,
    ): String = "KALSHI:KXS_H${event}_T$strike"

    fun contract(
        event: Int,
        strike: Int,
        trading: String? = null,
        openMs: Long = OPEN_MS,
    ): WireInstrument =
        WireInstrument(
            code(event, strike),
            "event",
            "USD",
            "1",
            "0.01",
            "1",
            "1",
            expiry = closeMs(event, openMs),
            category = "Financials",
            trading = trading,
            series = "KXS",
            event = "KXS-H$event",
            strikeType = "greater",
            floor = "${strike * 100}",
            openAt = openMs,
            closeAt = closeMs(event, openMs),
            title = "above ${strike * 100}",
            feeModel = "probability",
            takerFeeRate = "0.07",
            feeRounding = "0.01",
        )

    /** Lists [contracts] on [fake], beside nothing else. */
    fun list(
        fake: FakeGateway,
        contracts: List<WireInstrument>,
    ) {
        contracts.forEach { fake.instruments[it.code] = it }
        fake.codes = contracts.map { it.code }
    }

    /** Events 1 and 2 of strikes 1 to 4, listed from [openMs]. */
    fun ladder(openMs: Long = OPEN_MS): List<WireInstrument> =
        (1..2).flatMap { e -> (1..4).map { s -> contract(e, s, openMs = openMs) } }

    /** Event 1's ladder quoted at mids 0.90, 0.70, 0.40 and 0.10, a cent either side. */
    fun quotes(): List<WireQuote> =
        listOf("0.90", "0.70", "0.40", "0.10").mapIndexed { i, mid ->
            val m = mid.toBigDecimal()
            val cent = "0.01".toBigDecimal()
            WireQuote(code(1, i + 1), (m - cent).toPlainString(), (m + cent).toPlainString(), time = 1L)
        }
}
