package com.qkt.connector.gateway

import com.qkt.instrument.FundingRate
import com.qkt.instrument.FundingRateSource

/** A gateway's published funding rates (`/v1/funding-rates`), by qkt symbol through the gateway's listing. */
internal class GatewayFundingRates(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
) : FundingRateSource {
    override fun rates(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<FundingRate> {
        if (symbols.venue(qktSymbol) == null) symbols.updateListing(client.instruments())
        val code = symbols.venue(qktSymbol) ?: error("$qktSymbol is not in the gateway's listing")
        return client.fundingRates(code, fromMs, toMs).map {
            FundingRate(it.time, it.rate.toBigDecimal(), it.price?.toBigDecimal())
        }
    }
}
