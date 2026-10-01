package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant

/** A BTC_USDC option root trading a trade-built chain, one contract (a call unless [right] says put) and its stored quotes. */
internal class OptionChainFixture(
    val dataRoot: Path,
    takerFeeRate: String = "0",
    feeCapRate: String? = null,
    deliveryPrice: String? = "95000",
    right: String = "call",
) {
    val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.TRADE,
            markSpread = BigDecimal("0.05"),
            maxQuoteAgeMinutes = 60,
            takerFeeRate = BigDecimal(takerFeeRate),
            feeCapRate = feeCapRate?.let(::BigDecimal),
            deliveryFeeRate = BigDecimal("0.00015"),
        )
    private val code = if (right == "call") "C" else "P"
    val venueName = "BTC_USDC-2OCT26-92000-$code"
    val symbol = "DERIBIT:BTC_USDC_2OCT26_92000_$code"
    val expiryMs = ms("2026-10-02T08:00:00Z")
    val registry =
        OptionCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    OptionCatalog(
                        root.root,
                        listOf(OptionListing(venueName, "92000", right, expiryMs)),
                        deliveryPrice?.let { mapOf("2026-10-02" to it) }.orEmpty(),
                    ),
            ),
            dataRoot,
        )

    /** Stores one trade-built snapshot of the call per (ISO instant, mark, age ms). */
    fun store(vararg quotes: Triple<String, String, Long>) {
        val snapshots =
            quotes.map { (at, mark, age) ->
                val atMs = ms(at)
                val quote =
                    ChainQuote(
                        atMs,
                        venueName,
                        null,
                        null,
                        BigDecimal(mark),
                        BigDecimal("50"),
                        BigDecimal("83000"),
                        null,
                        age,
                        QuoteSource.TRADE,
                    )
                ChainSnapshot(root.root, atMs, listOf(quote))
            }
        ChainSnapshotStore(dataRoot, QuoteSource.TRADE).write(root.root, snapshots)
    }

    companion object {
        fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    }
}
