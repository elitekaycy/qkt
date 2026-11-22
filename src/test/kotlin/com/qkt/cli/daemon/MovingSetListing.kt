package com.qkt.cli.daemon

import com.qkt.common.Side
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.EventContracts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** A gateway `GW` listing hourly event contracts around [NOW], and the one-set strategy `rolling` that ranks them. */
internal object MovingSetListing {
    const val HOUR = 3_600_000L
    const val NOW = 1_794_002_400_000L

    /** Contract [code] of the gateway's listing, open from [open] hours after [NOW] until [expiry] hours. */
    fun contract(
        code: String,
        open: Int,
        expiry: Int,
    ): InstrumentMeta =
        EventContracts.meta(
            "GW:$code",
            EventContracts.terms().copy(
                openAtMs = NOW + open * HOUR,
                closeAtMs = NOW + expiry * HOUR,
                expiryMs = NOW + expiry * HOUR,
            ),
        )

    fun listing(vararg rows: InstrumentMeta): SetCatalog =
        object : SetCatalog {
            override val source = "the venue listing"
            override val survivorsOnly = false

            override fun venue(
                alias: String,
                spec: SymbolSetSpec.Filtered,
            ) = VenueCatalog(rows.toList())
        }

    /** `rolling.qkt` in [dir]: the top contract by symbol each hour, bought above 0.5. */
    fun strategy(dir: Path): Path =
        dir.resolve("rolling.qkt").also {
            Files.writeString(
                it,
                """
                STRATEGY rolling VERSION 1
                SYMBOLS
                    m = GW:* WHERE kind = event TOP 1 BY symbol DESC DYNAMIC EVERY 1h
                RULES
                    FOR EACH s IN m DO
                      WHEN s.close > 0.5 THEN BUY s SIZING 1
                """.trimIndent(),
            )
        }

    /** A leg book holding one contract of [symbol]. */
    fun held(symbol: String): LegBook =
        LegBook(symbol).also {
            it.add(
                PositionLeg(
                    legId = "l-$symbol",
                    parentLegId = null,
                    role = LegRole.PRIMARY,
                    side = Side.BUY,
                    symbol = symbol,
                    quantity = BigDecimal.ONE,
                    entryPrice = BigDecimal.ONE,
                    openedAt = 0L,
                ),
            )
        }
}
