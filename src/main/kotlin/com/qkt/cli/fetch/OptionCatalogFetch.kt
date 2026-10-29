package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionRoot
import com.qkt.marketdata.store.deribit.DeribitClient
import com.qkt.marketdata.store.deribit.DeribitOptionCatalog
import java.io.IOException
import java.nio.file.Path

/**
 * `qkt fetch <VENUE>:<ROOT> --catalog`: builds the option catalog of a root declared under `options:`
 * in the data root's `instruments.yaml` (its currency, contract terms and settlement index come from
 * there) and writes it to `contracts/<VENUE>/<ROOT>.options.json`, merged with what was there so contracts
 * that fall out of the venue's listing stay resolvable. The catalog comes from the account's own venue
 * listing when the `brokers:` account named after the venue lists options (a gateway account, whatever it is
 * named: a testnet's listing is not its mainnet's); only `DERIBIT` without such an account reads Deribit's
 * public listing. A venue that lists options of the root of which none can be catalogued is refused.
 */
internal object OptionCatalogFetch {
    /** Whether [venue]'s options can be catalogued from a public listing without an account. */
    fun handles(venue: String): Boolean = venue == DeribitClient.VENUE

    /** Deribit's public listing. */
    private val public = {
        root: OptionRoot,
        warn: (String) -> Unit,
        ->
        DeribitOptionCatalog(DeribitClient()).build(root, warn)
    }

    /** Builds [target]'s catalog from [account]'s listing, else the public one; returns a process exit code. */
    fun forRoot(
        target: String,
        dataRoot: Path,
        account: CatalogAccount,
    ): Int {
        val venue = target.substringBefore(':')
        val listing = account.account?.optionCatalogs
        if (account.failed) return ExitCodes.USER_ERROR
        if (listing != null) return run(target, dataRoot, { listing.listed(target) }, listing::build)
        if (handles(venue)) return run(target, dataRoot, build = public)
        System.err.println(
            "qkt: no option catalog source for '$venue' (a type: gateway account of that name in --config, " +
                "or ${DeribitClient.VENUE}'s public listing)",
        )
        return ExitCodes.USER_ERROR
    }

    /** Says on stderr when [account]'s venue lists options of [target], a root not declared under `options:`. */
    fun warnUndeclared(
        target: String,
        account: CatalogAccount,
    ) {
        val listed = account.account?.optionCatalogs?.let { runCatching { it.listed(target) }.getOrNull() } ?: return
        if (listed == 0) return
        System.err.println(
            "qkt: warning: ${target.substringBefore(':')} lists $listed options of $target, none catalogued: " +
                "declare the root under options: in instruments.yaml to trade them",
        )
    }

    /**
     * Builds and stores [target]'s option catalog with [build]; returns a process exit code. Refused when
     * the build catalogued nothing while the venue lists options of the root ([listed]).
     */
    fun run(
        target: String,
        dataRoot: Path,
        listed: () -> Int? = { null },
        build: (
            OptionRoot,
            (String) -> Unit,
        ) -> OptionCatalog = public,
    ): Int {
        val root = declaredOptionRoot(target, dataRoot) ?: return ExitCodes.USER_ERROR
        val built =
            try {
                build(root) { System.err.println("qkt: warning: $it") }
            } catch (e: IOException) {
                return failed(target, e)
            } catch (e: IllegalStateException) {
                return failed(target, e)
            } catch (e: IllegalArgumentException) {
                return failed(target, e)
            }
        if (built.contracts.isEmpty()) {
            val count = listed() ?: 0
            if (count > 0) {
                System.err.println(
                    "qkt: ${target.substringBefore(':')} lists $count options of $target but none could be " +
                        "catalogued (see the warnings above); nothing written",
                )
                return ExitCodes.USER_ERROR
            }
        }
        val store = OptionCatalogStore(dataRoot)
        val catalog = merged(store.read(target), built)
        store.write(catalog)
        val counts = "${catalog.contracts.size} options and ${catalog.deliveryPrices.size} delivery prices"
        println("qkt fetch: $counts for $target -> ${store.path(target)}")
        return ExitCodes.SUCCESS
    }

    /**
     * [built] plus every contract of [existing] the venue no longer lists (its history window rolls,
     * and older backtests still resolve them); fresh records and delivery prices win on overlap.
     */
    private fun merged(
        existing: OptionCatalog?,
        built: OptionCatalog,
    ): OptionCatalog {
        if (existing == null) return built
        val fresh = built.contracts.map { it.symbol }.toSet()
        val contracts =
            (built.contracts + existing.contracts.filter { it.symbol !in fresh })
                .sortedWith(compareBy({ it.expiryMs }, { it.strike.toBigDecimal() }, { it.right }))
        return OptionCatalog(built.root, contracts, existing.deliveryPrices + built.deliveryPrices)
    }

    private fun failed(
        target: String,
        cause: Exception,
    ): Int {
        System.err.println("qkt: could not build the option catalog for $target: ${cause.message}")
        return ExitCodes.USER_ERROR
    }
}
