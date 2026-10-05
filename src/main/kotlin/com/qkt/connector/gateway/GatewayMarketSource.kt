package com.qkt.connector.gateway

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.marketdata.Candle
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.Print
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.marks.MarkPrices
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.UnsupportedDataException
import java.math.BigDecimal
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.slf4j.LoggerFactory

/**
 * Live prices from a VGP v1 gateway's quotes socket (`GET /v1/quotes`), for the account whose symbols
 * carry [prefix] (`DERIBIT:`). It serves the account's contracts and its option roots as whole feeds
 * (`OPTIONS:DERIBIT.BTC_USDC`, every listed option of the root, including ones listed later). Each
 * quote becomes one tick ([gatewayQuoteTick]). [listing] is read once per subscription to check every
 * requested contract and root is listed: an unknown one fails the subscription rather than staying silent.
 * The quotes of a fed root also go to [recorderFor] the root (`DERIBIT:BTC_USDC`), when it records one.
 * Closed bars of a contract come from `GET /v1/bars` through [bars] (live warmup reads them); a whole
 * option root has none. Each quote's mark and index are kept for strategies to read ([GatewayMarks]), when
 * the gateway's [capabilities] include `mark_prices`, and each option quote's mark IV and forward
 * ([GatewayOptionMarks]), when they include `option_marks`. Trade flow is read from the gateway's tape through
 * [prints] ([GatewayTradeFlow]), when they include `trades` or `liquidations`.
 */
internal class GatewayMarketSource(
    private val prefix: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val listing: () -> List<WireInstrument>,
    private val recorderFor: (String) -> ((WireQuote) -> Unit)? = { null },
    private val bars: (code: String, windowMs: Long, fromMs: Long, toMs: Long) -> List<WireBar> = { _, _, _, _ ->
        emptyList()
    },
    prints: (code: String, kind: FlowKind, fromMs: Long, toMs: Long) -> List<Print> = { _, _, _, _ -> emptyList() },
    capabilities: () -> Collection<String> = { emptyList() },
) : MarketSource {
    private val marks = GatewayMarks(prefix, capabilities)

    private val optionMarks = GatewayOptionMarks(prefix, capabilities)

    private val flow = GatewayTradeFlow(prefix, capabilities, prints)

    private val rootPrefix = OptionRootSymbol.PREFIX + prefix.removeSuffix(":") + "."

    private val log = LoggerFactory.getLogger(GatewayMarketSource::class.java)

    override val name: String = "gateway"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    override fun supports(symbol: String): Boolean = symbol.startsWith(prefix) || symbol.startsWith(rootPrefix)

    /** The account's contracts' quoted marks ([GatewayMarks]); an option root's whole feed has none. */
    override fun marksFor(symbol: String): MarkPrices? = marks.takeIf { symbol.startsWith(prefix) }

    /** The account's contracts' quoted option marks ([GatewayOptionMarks]); an option root's whole feed has none. */
    override fun optionMarksFor(symbol: String): OptionMarks? = optionMarks.takeIf { symbol.startsWith(prefix) }

    /** The account's contracts' tape and liquidations, read from the gateway ([GatewayTradeFlow]). */
    override fun tradeFlowFor(symbol: String): TradeFlow? = flow.takeIf { symbol.startsWith(prefix) }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val ms = window.durationMs
        if (!symbol.startsWith(prefix) || ms % MINUTE_MS != 0L || DAY_MS % ms != 0L) {
            throw UnsupportedDataException(MarketSourceCapability.BARS, "gateway bars of $symbol every $ms ms")
        }
        val listed = GatewaySymbols(prefix).apply { update(listing().map { it.code }) }
        val code = requireNotNull(listed.code(symbol)) { "gateway does not list $symbol" }
        return bars(code, ms, range.from.toEpochMilli(), range.to.toEpochMilli()).asSequence().map {
            Candle(
                symbol,
                BigDecimal(it.open),
                BigDecimal(it.high),
                BigDecimal(it.low),
                BigDecimal(it.close),
                BigDecimal(it.volume),
                it.start,
                it.start + ms,
            )
        }
    }

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all(::supports)) { "gateway $prefix does not serve ${symbols.filterNot(::supports)}" }
        val instruments = listing().also(marks::listed).also(optionMarks::listed).also(flow::listed)
        val gatewaySymbols = GatewaySymbols(prefix).apply { update(instruments.map { it.code }) }
        val fedRoots = symbols.filter { it.startsWith(rootPrefix) }.map { OptionRootSymbol.parse(it).getOrThrow() }
        val listedRoots = instruments.mapNotNull { it.underlying }.toSet()
        val roots = fedRoots.map { it.root.substringAfter(':') }
        val unlisted = roots.filterNot { it in listedRoots }
        require(unlisted.isEmpty()) { "gateway lists no options of $unlisted" }
        // A contract of a fed root already arrives with its root, so it is not asked for twice.
        val contracts = symbols.filter { s -> s.startsWith(prefix) && fedRoots.none { it.covers(s) } }
        val codes = contracts.map { requireNotNull(gatewaySymbols.venue(it)) { "gateway does not list $it" } }
        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("v1/quotes")
                .apply { if (codes.isNotEmpty()) addQueryParameter("symbols", codes.joinToString(",")) }
                .apply { if (roots.isNotEmpty()) addQueryParameter("roots", roots.joinToString(",")) }
                .build()
                .toString()
        val recorders = HashMap<String, (WireQuote) -> Unit>()
        for (fed in fedRoots) {
            val recorder = recorderFor(fed.root)
            if (recorder != null) recorders[fed.root.substringAfter(':')] = recorder else log.warn(UNRECORDED, fed.root)
        }

        fun record(quote: WireQuote) {
            marks.heard(quote)
            optionMarks.heard(quote)
            recorders[quote.symbol.substringBefore('-')]?.invoke(quote)
        }
        return LiveTickFeed(GatewayQuoteSource(url, apiKey, gatewaySymbols::qkt, ::record))
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L

        const val UNRECORDED =
            "option root {} is fed live but declares no book chain series (chains: book): nothing records " +
                "its live chain, so structures and chain metrics see only snapshots written elsewhere"
    }
}
