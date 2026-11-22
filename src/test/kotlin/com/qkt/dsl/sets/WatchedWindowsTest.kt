package com.qkt.dsl.sets

import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.SetMembership
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WatchedWindowsTest {
    private val watched = WatchedWindows(MINUTE, setOf("BACKTEST:A", "BACKTEST:B"))

    private fun tick(
        symbol: String,
        price: String,
        atMs: Long,
        volume: String? = null,
        bid: String? = null,
        ask: String? = null,
    ) = watched.onTick(
        Tick(symbol, BigDecimal(price), atMs, volume?.toBigDecimal(), bid?.toBigDecimal(), ask?.toBigDecimal()),
    )

    private fun filtered(text: String) = SymbolSetSpec.parse(text) as SymbolSetSpec.Filtered

    private fun read(
        field: CatalogField,
        symbol: String,
        closeMs: Long,
    ) = watched.asOf(closeMs).read(field, symbol)

    @Test
    fun `a close reads each symbol's last window that ended at or before it`() {
        tick("BACKTEST:A", "10", T0 + 1_000L, volume = "2", bid = "9.9", ask = "10.1")
        tick("BACKTEST:A", "11", T0 + 2_000L, volume = "3", bid = "10.9", ask = "11.2")
        tick("BACKTEST:A", "12", T0 + 3_000L)
        assertThat(read(CatalogField.CLOSE, "BACKTEST:A", T0)).isNull()
        assertThat(read(CatalogField.CLOSE, "BACKTEST:A", T0 + MINUTE)).isEqualByComparingTo("12")
        assertThat(read(CatalogField.VOLUME, "BACKTEST:A", T0 + MINUTE)).isEqualByComparingTo("5")
        assertThat(read(CatalogField.SPREAD, "BACKTEST:A", T0 + MINUTE)).isEqualByComparingTo("0.3")
        tick("BACKTEST:A", "13", T0 + MINUTE + 1_000L, volume = "7")
        assertThat(read(CatalogField.CLOSE, "BACKTEST:A", T0 + MINUTE)).isEqualByComparingTo("12")
        assertThat(read(CatalogField.VOLUME, "BACKTEST:A", T0 + 2 * MINUTE)).isEqualByComparingTo("7")
        assertThat(read(CatalogField.SPREAD, "BACKTEST:A", T0 + 2 * MINUTE)).isNull()
        assertThat(read(CatalogField.CLOSE, "BACKTEST:A", T0 + 5 * MINUTE)).isEqualByComparingTo("13")
    }

    @Test
    fun `an unwatched or silent symbol and a catalog field read null`() {
        tick("BACKTEST:C", "10", T0 + 1_000L, volume = "2")
        tick("BACKTEST:B", "10", T0 + 1_000L)
        assertThat(read(CatalogField.CLOSE, "BACKTEST:C", T0 + MINUTE)).isNull()
        assertThat(read(CatalogField.VOLUME, "BACKTEST:B", T0 + MINUTE)).isNull()
        assertThat(read(CatalogField.SPREAD, "BACKTEST:B", T0 + MINUTE)).isNull()
        assertThat(read(CatalogField.SYMBOL, "BACKTEST:B", T0 + MINUTE)).isNull()
        assertThat(read(CatalogField.CLOSE, "BACKTEST:A", T0 + MINUTE)).isNull()
    }

    @Test
    fun `a set reading market fields moves, without DYNAMIC, and only a run may evaluate it`() {
        val spec = filtered("BACKTEST:* WHERE kind = event AND volume > 0 TOP 1 BY volume")
        assertThat(spec.dynamic).isFalse()
        assertThat(spec.readsMarket).isTrue()
        assertThat(spec.moves).isTrue()
        assertThat(SymbolSetSpec.parse(spec.canonical)).isEqualTo(spec)
        val byClose = filtered("BACKTEST:* WHERE kind = event TOP 1 BY close")
        assertThat(byClose.readsMarket).isTrue()
        val fixed = filtered("BACKTEST:* WHERE kind = event TOP 1 BY symbol")
        assertThat(fixed.moves).isFalse()
        assertThatThrownBy { DynamicSetMembership(fixed, DynamicSetFixtures.rows, MINUTE) }
            .hasMessageEndingWith("it has no runtime membership")
        val membership = DynamicSetMembership(spec, DynamicSetFixtures.rows, MINUTE)
        assertThat(membership.readsMarket).isTrue()
        assertThat(membership.venue).isEqualTo("BACKTEST")
        assertThat(membership.windowMs).isEqualTo(MINUTE)
        tick("BACKTEST:A", "10", T0 + 1_000L, volume = "4")
        assertThat(membership.membersAt(T0 + MINUTE, watched.asOf(T0 + MINUTE))).containsExactly("A")
        assertThat(membership.membersAt(T0 + MINUTE)).isEmpty()
        assertThat(SetMembership().step(membership.membersAt(T0 + MINUTE), emptySet()).changes).isEmpty()
    }
}
