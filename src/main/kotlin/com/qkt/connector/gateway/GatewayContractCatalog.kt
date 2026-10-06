package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogSource
import com.qkt.instrument.ListedContract

/**
 * A futures root's catalog from a gateway account's listing: every dated contract of the root the
 * gateway lists, with its expiry, and, once it has expired, its settlement price as its delivery price.
 * A gateway reports only the settlements of contracts the account held, so other expired contracts stay
 * unpriced (a backtest holding one into expiry settles it at its last price). The gateway lists a
 * contract for only 30 days after expiry, so a stored catalog keeps the older ones (`qkt fetch --catalog`
 * merges).
 */
internal class GatewayContractCatalog(
    private val client: GatewayClient,
    private val symbols: GatewaySymbols,
    private val clock: Clock,
) : ContractCatalogSource {
    override fun build(
        root: String,
        warn: (String) -> Unit,
    ): ContractCatalog {
        require(symbols.owns(root)) { "$root is not a root of this gateway account" }
        val family = root.substringAfter(':')
        val contracts =
            client.instruments().mapNotNull { instrument ->
                val expiry = instrument.expiry?.takeIf { instrument.kind == "future" } ?: return@mapNotNull null
                val name = symbols.qkt(instrument.code).substringAfter(':')
                if (!name.startsWith(family) || name.length == family.length) return@mapNotNull null
                ListedContract(name, expiry, deliveryPrice(instrument.code, expiry, warn))
            }
        return ContractCatalog(root, contracts).sorted()
    }

    private fun deliveryPrice(
        code: String,
        expiryMs: Long,
        warn: (String) -> Unit,
    ): String? {
        if (clock.now() < expiryMs) return null
        return try {
            client.settlementsOf(code).firstOrNull()?.price
        } catch (e: GatewayException) {
            null.also { warn("settlement of $code unavailable (${e.message}); written without a delivery price") }
        } catch (e: GatewayUnavailableException) {
            null.also { warn("settlement of $code unavailable (${e.message}); written without a delivery price") }
        }
    }
}
