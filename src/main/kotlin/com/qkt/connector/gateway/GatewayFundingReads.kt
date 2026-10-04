package com.qkt.connector.gateway

/** `GET /v1/funding`: what the venue charged or credited the account for perpetuals from [fromMs] to [toMs], oldest first. */
fun GatewayClient.funding(
    fromMs: Long,
    toMs: Long,
): List<WireFunding> = read("/v1/funding?from=$fromMs&to=$toMs", WireFundings.serializer()).funding

/** `GET /v1/funding-rates`: every published funding rate of perpetual [code] from [fromMs] to [toMs], oldest first. */
fun GatewayClient.fundingRates(
    code: String,
    fromMs: Long,
    toMs: Long,
): List<WireFundingRate> {
    val rates = ArrayList<WireFundingRate>()
    var from: Long? = fromMs
    while (from != null && from <= toMs) {
        val page = read("/v1/funding-rates?symbol=$code&from=$from&to=$toMs", WireFundingRates.serializer())
        rates += page.rates
        require(page.next == null || page.next > from) { "gateway funding rates of $code do not advance past $from" }
        from = page.next
    }
    return rates
}
