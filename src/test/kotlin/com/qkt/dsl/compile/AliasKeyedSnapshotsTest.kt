package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AliasKeyedSnapshotsTest {
    @Test
    fun `same symbol two aliases maintain independent rolling history`() {
        val store = SnapshotStore(mapOf("fast" to 5))
        store.pushRolling("btc", "fast", BigDecimal("100"))
        store.pushRolling("btc_h1", "fast", BigDecimal("200"))
        store.pushRolling("btc", "fast", BigDecimal("110"))
        store.pushRolling("btc_h1", "fast", BigDecimal("210"))

        assertThat(store.readRolling("btc", "fast", 0)).isEqualByComparingTo("110")
        assertThat(store.readRolling("btc_h1", "fast", 0)).isEqualByComparingTo("210")
        assertThat(store.readRolling("btc", "fast", 1)).isEqualByComparingTo("100")
        assertThat(store.readRolling("btc_h1", "fast", 1)).isEqualByComparingTo("200")
    }

    @Test
    fun `same symbol two aliases maintain independent slot snapshots`() {
        val store = SnapshotStore(emptyMap())
        store.captureSlot("btc", "fast", SnapshotOpen, BigDecimal("100"))
        store.captureSlot("btc_h1", "fast", SnapshotOpen, BigDecimal("999"))
        assertThat(store.readSlot("btc", "fast", SnapshotOpen)).isEqualByComparingTo("100")
        assertThat(store.readSlot("btc_h1", "fast", SnapshotOpen)).isEqualByComparingTo("999")
    }

    @Test
    fun `clearSlot only clears the targeted alias`() {
        val store = SnapshotStore(emptyMap())
        store.captureSlot("btc", "fast", SnapshotOpen, BigDecimal("100"))
        store.captureSlot("btc_h1", "fast", SnapshotOpen, BigDecimal("999"))
        store.clearSlot("btc", "fast", SnapshotOpen)
        assertThat(store.readSlot("btc", "fast", SnapshotOpen)).isNull()
        assertThat(store.readSlot("btc_h1", "fast", SnapshotOpen)).isEqualByComparingTo("999")
    }

    @Test
    fun `slot kinds are independent per alias`() {
        val store = SnapshotStore(emptyMap())
        store.captureSlot("btc", "fast", SnapshotBuy, BigDecimal("100"))
        store.captureSlot("btc", "fast", SnapshotOpen, BigDecimal("200"))
        assertThat(store.readSlot("btc", "fast", SnapshotBuy)).isEqualByComparingTo("100")
        assertThat(store.readSlot("btc", "fast", SnapshotOpen)).isEqualByComparingTo("200")
    }

    @Test
    fun `aggregate binding bag groups by alias not symbol`() {
        val bag = AggregateBinding.Bag()
        val state1 = AggregateState.sinceOpen(com.qkt.dsl.ast.AggFn.MAX)
        val state2 = AggregateState.sinceOpen(com.qkt.dsl.ast.AggFn.MAX)
        val b1 =
            AggregateBinding(CompiledExpr { Value.Undefined }, com.qkt.dsl.ast.SinceOpen, state1, ruleAlias = "btc")
        val b2 =
            AggregateBinding(CompiledExpr { Value.Undefined }, com.qkt.dsl.ast.SinceOpen, state2, ruleAlias = "btc_h1")
        bag.add(b1)
        bag.add(b2)
        assertThat(bag.bindingsForAlias("btc")).containsExactly(b1)
        assertThat(bag.bindingsForAlias("btc_h1")).containsExactly(b2)
    }
}
