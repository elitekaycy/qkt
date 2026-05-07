package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SnapshotStoreTest {
    @Test
    fun `single-slot store and read`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        s.captureSlot("BTCUSDT", "fast", SnapshotBuy, BigDecimal("105"))
        assertThat(s.readSlot("BTCUSDT", "fast", SnapshotBuy)).isEqualByComparingTo("105")
    }

    @Test
    fun `unset slot returns null`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        assertThat(s.readSlot("BTCUSDT", "fast", SnapshotSell)).isNull()
    }

    @Test
    fun `most-recent capture wins`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        s.captureSlot("BTCUSDT", "x", SnapshotBuy, BigDecimal("1"))
        s.captureSlot("BTCUSDT", "x", SnapshotBuy, BigDecimal("2"))
        assertThat(s.readSlot("BTCUSDT", "x", SnapshotBuy)).isEqualByComparingTo("2")
    }

    @Test
    fun `clear slot returns null on read`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        s.captureSlot("BTCUSDT", "fast", SnapshotOpen, BigDecimal("105"))
        s.clearSlot("BTCUSDT", "fast", SnapshotOpen)
        assertThat(s.readSlot("BTCUSDT", "fast", SnapshotOpen)).isNull()
    }

    @Test
    fun `rolling buffer returns latest at offset 0 and prev at offset 1`() {
        val s = SnapshotStore(maxRollingPerName = mapOf("close" to 3))
        s.pushRolling("BTCUSDT", "close", BigDecimal("100"))
        s.pushRolling("BTCUSDT", "close", BigDecimal("110"))
        s.pushRolling("BTCUSDT", "close", BigDecimal("120"))
        assertThat(s.readRolling("BTCUSDT", "close", 0)).isEqualByComparingTo("120")
        assertThat(s.readRolling("BTCUSDT", "close", 1)).isEqualByComparingTo("110")
        assertThat(s.readRolling("BTCUSDT", "close", 2)).isEqualByComparingTo("100")
    }

    @Test
    fun `rolling out-of-range returns null`() {
        val s = SnapshotStore(maxRollingPerName = mapOf("close" to 2))
        s.pushRolling("BTCUSDT", "close", BigDecimal("100"))
        assertThat(s.readRolling("BTCUSDT", "close", 1)).isNull()
        assertThat(s.readRolling("BTCUSDT", "close", 5)).isNull()
    }

    @Test
    fun `rolling never registered returns null`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        assertThat(s.readRolling("BTCUSDT", "close", 0)).isNull()
    }
}
