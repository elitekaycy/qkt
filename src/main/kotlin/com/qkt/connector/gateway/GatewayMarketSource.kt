package com.qkt.connector.gateway

import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.slf4j.LoggerFactory

/**
 * Live prices from a VGP v1 gateway's quotes socket (`GET /v1/quotes`), for the account whose symbols
 * carry [prefix] (`DERIBIT:`). It serves the account's contracts and its option roots as whole feeds
 * (`OPTIONS:DERIBIT.BTC_USDC`, every listed option of the root, including ones listed later). Each
 * quote becomes one tick ([gatewayQuoteTick]). [listing] is read once per subscription to check every
 * requested contract and root is listed: an unknown one fails the subscription rather than staying silent.
 * The quotes of a fed root also go to [recorderFor] the root (`DERIBIT:BTC_USDC`), when it records one.
 */
internal class GatewayMarketSource(
    private val prefix: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val listing: () -> List<WireInstrument>,
    private val recorderFor: (String) -> ((WireQuote) -> Unit)? = { null },
) : MarketSource {
    private val rootPrefix = OptionRootSymbol.PREFIX + prefix.removeSuffix(":") + "."

    private val log = LoggerFactory.getLogger(GatewayMarketSource::class.java)

    override val name: String = "gateway"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String): Boolean = symbol.startsWith(prefix) || symbol.startsWith(rootPrefix)

    override fun liveTicks(symbols: List<String>): TickFeed {
        require(symbols.all(::supports)) { "gateway $prefix does not serve ${symbols.filterNot(::supports)}" }
        val instruments = listing()
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
            recorders[quote.symbol.substringBefore('-')]?.invoke(quote)
        }
        return LiveTickFeed(GatewayQuoteSource(url, apiKey, gatewaySymbols::qkt, ::record))
    }

    private companion object {
        const val UNRECORDED =
            "option root {} is fed live but declares no book chain series (chains: book): nothing records " +
                "its live chain, so structures and chain metrics see only snapshots written elsewhere"
    }
}
