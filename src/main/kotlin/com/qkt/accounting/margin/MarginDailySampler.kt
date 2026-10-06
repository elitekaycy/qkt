package com.qkt.accounting.margin

import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One UTC day's margin at its last sample: initial margin in use, maintenance, account equity. */
data class MarginDay(
    val date: LocalDate,
    val marginUsed: BigDecimal,
    val maintenance: BigDecimal,
    val equity: BigDecimal,
) {
    /** Whether equity had fallen below maintenance: the venue would call for margin. */
    val marginCall: Boolean get() = equity < maintenance
}

/**
 * Samples the account's futures margin on every event it is told about ([onTime]) and keeps the
 * last sample of each UTC day, for `margin_daily.csv`. Only days that end holding a position with
 * margin terms produce a row. Equity comes from the supplier given to [bind]. With [options], held
 * option positions add their worst-case requirement ([OptionMargin]), the one amount the margin rule
 * holds them to, to both the margin in use and maintenance.
 */
class MarginDailySampler(
    private val margin: MarginModel,
    private val prices: MarketPriceProvider,
    private val positions: PositionProvider,
    private val options: OptionMargin? = null,
) {
    private val days = mutableListOf<MarginDay>()
    private var equity: () -> BigDecimal = { BigDecimal.ZERO }
    private var last: MarginDay? = null

    /** Read account equity from [equity] from now on. */
    fun bind(equity: () -> BigDecimal) {
        this.equity = equity
    }

    /** Sample the book as of [nowMs], closing the previous day's row when [nowMs] starts a new day. */
    fun onTime(nowMs: Long) {
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        last?.takeIf { it.date != date }?.let(days::add)
        last = sample(date, nowMs)
    }

    /** Every day's row so far, the current day as of its latest sample. */
    val rows: List<MarginDay> get() = days + listOfNotNull(last)

    private fun sample(
        date: LocalDate,
        nowMs: Long,
    ): MarginDay? {
        var used = BigDecimal.ZERO
        var maintenance = BigDecimal.ZERO
        var margined = false
        for (symbol in positions.symbols()) {
            if (!margin.hasTerms(symbol)) continue
            val position = positions.positionFor(symbol) ?: continue
            if (position.quantity.signum() == 0) continue
            val price = prices.lastPrice(symbol) ?: position.avgEntryPrice
            used = used.add(margin.initial(symbol, position.quantity, price, nowMs))
            maintenance = maintenance.add(margin.maintenance(symbol, position.quantity, price, nowMs))
            margined = true
        }
        val mark = { s: String -> prices.lastPrice(s) ?: positions.positionFor(s)?.avgEntryPrice }
        val held =
            positions.symbols().any {
                options?.covers(it) == true &&
                    positions.positionFor(it)?.quantity?.signum() != 0
            }
        val optionsRequired = options?.takeIf { held }?.requiredWithFills(emptyMap(), positions, mark)
        if (optionsRequired is OptionMargin.Outcome.Required) {
            used = used.add(optionsRequired.amount)
            maintenance = maintenance.add(optionsRequired.amount)
            margined = true
        }
        return if (margined) MarginDay(date, used, maintenance, equity()) else null
    }
}
