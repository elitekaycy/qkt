package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogSource
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.OptionSymbols
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * An option root's catalog from a gateway account's own listing, whatever the account is named: every
 * option the gateway lists on the root's underlying (`BTC_USDC` for `PAPER_DERIBIT_B:BTC_USDC`), with the
 * listing's strike, right and expiry, so the catalog is the account's (a testnet lists other contracts than
 * its mainnet). An option priced in another currency than the root's, or named off the
 * `<UNDERLYING>-<EXPIRY>-<STRIKE>-<C|P>` form, is skipped and reported; a listing whose contract size,
 * base tick or minimum trade disagrees with the declared root fails the build.
 *
 * VGP v1 lists no index delivery prices. A day's delivery price is known exactly when the account held an
 * option of the root expiring that day which settled in the money (a call settles at delivery minus strike, a
 * put at strike minus delivery); other days stay unpriced here, and a stored catalog keeps the prices it
 * already has (`qkt fetch --catalog` merges).
 */
internal class GatewayOptionCatalog(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
    private val clock: Clock,
) : OptionCatalogSource {
    override fun listed(root: String): Int = options(root).size

    override fun build(
        root: OptionRoot,
        warn: (String) -> Unit,
    ): OptionCatalog {
        require(symbols.owns(root.root)) { "${root.root} is not a root of this gateway account" }
        val listings =
            options(root.root)
                .mapNotNull { listing(it, root, warn) }
                .sortedWith(compareBy({ it.expiryMs }, { BigDecimal(it.strike) }, { it.right }))
        return OptionCatalog(root.root, listings, deliveryPrices(listings, warn))
    }

    private fun options(root: String): List<WireInstrument> {
        val underlying = root.substringAfter(':')
        return client.instruments().filter { it.kind == "option" && it.underlying == underlying }
    }

    private fun listing(
        instrument: WireInstrument,
        root: OptionRoot,
        warn: (String) -> Unit,
    ): OptionListing? {
        val code = instrument.code
        if (instrument.currency != root.currency) {
            warn("skipped $code: priced in ${instrument.currency}; ${root.root} trades ${root.currency}")
            return null
        }
        requireMatches(root, code, "contractSize", root.contractSize, instrument.contractSize)
        requireMatches(root, code, "tickSize", root.tickSteps.base, instrument.tickSize)
        requireMatches(root, code, "volumeMin", root.volumeMin, instrument.volumeMin)
        val strike = instrument.strike?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val right = instrument.right?.takeIf { it == "call" || it == "put" }
        val expiry = instrument.expiry
        val named = runCatching { OptionSymbols.qktCode(code) }.isSuccess
        if (strike == null || right == null || expiry == null || !named) {
            warn("skipped $code: listed without a usable strike, right, expiry or option name")
            return null
        }
        return OptionListing(code, strike.stripTrailingZeros().toPlainString(), right, expiry)
    }

    private fun requireMatches(
        root: OptionRoot,
        code: String,
        field: String,
        declared: BigDecimal,
        listed: String,
    ) = check(listed.toBigDecimalOrNull()?.compareTo(declared) == 0) {
        "${root.root} declares $field ${declared.toPlainString()} but the gateway lists $listed for $code; " +
            "fix instruments.yaml"
    }

    /** Each expired day's delivery price, from the account's in-the-money settlements of the root's options. */
    private fun deliveryPrices(
        listings: List<OptionListing>,
        warn: (String) -> Unit,
    ): Map<String, String> {
        val now = clock.now()
        val expired = listings.filter { it.expiryMs <= now }.associateBy { it.symbol }
        if (expired.isEmpty()) return emptyMap()
        val settled =
            try {
                client.settlements(expired.values.minOf { it.expiryMs }, now)
            } catch (e: GatewayException) {
                return emptyMap<String, String>().also {
                    warn(
                        "settlements unavailable (${e.message}); no delivery prices",
                    )
                }
            } catch (e: GatewayUnavailableException) {
                return emptyMap<String, String>().also {
                    warn(
                        "settlements unavailable (${e.message}); no delivery prices",
                    )
                }
            }
        val prices = sortedMapOf<String, String>()
        for (settlement in settled) {
            val option = expired[settlement.symbol] ?: continue
            val intrinsic = settlement.price.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
            val strike = BigDecimal(option.strike)
            val delivery = if (option.right == "call") strike.add(intrinsic) else strike.subtract(intrinsic)
            prices[
                Instant
                    .ofEpochMilli(option.expiryMs)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString(),
            ] =
                delivery.stripTrailingZeros().toPlainString()
        }
        return prices
    }
}
