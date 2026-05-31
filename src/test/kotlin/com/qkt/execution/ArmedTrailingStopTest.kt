package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #48 — invariants on the [OrderRequest.ArmedTrailingStop] value type. Higher-level
 * arming, hwm-tracking, and trigger semantics are exercised end-to-end by
 * [com.qkt.dsl.compile.ArmedTrailEndToEndTest].
 */
class ArmedTrailingStopTest {
    private fun build(
        side: Side = Side.SELL,
        entry: String = "100",
        distance: String = "5",
        threshold: String = "10",
    ) = OrderRequest.ArmedTrailingStop(
        id = "ats-1",
        symbol = "X",
        side = side,
        quantity = BigDecimal("1"),
        entryPrice = BigDecimal(entry),
        trailDistance = BigDecimal(distance),
        mfeThreshold = BigDecimal(threshold),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `builds with required fields`() {
        val s = build()
        assertThat(s.id).isEqualTo("ats-1")
        assertThat(s.entryPrice).isEqualByComparingTo("100")
        assertThat(s.trailDistance).isEqualByComparingTo("5")
        assertThat(s.mfeThreshold).isEqualByComparingTo("10")
    }

    @Test
    fun `rejects non-positive quantity`() {
        assertThatThrownBy {
            OrderRequest.ArmedTrailingStop(
                id = "x",
                symbol = "X",
                side = Side.SELL,
                quantity = BigDecimal.ZERO,
                entryPrice = BigDecimal("100"),
                trailDistance = BigDecimal("5"),
                mfeThreshold = BigDecimal("10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects non-positive trailDistance`() {
        assertThatThrownBy { build(distance = "0") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts zero mfeThreshold (trail-from-inception)`() {
        val s = build(threshold = "0")
        assertThat(s.mfeThreshold).isEqualByComparingTo("0")
    }

    @Test
    fun `rejects negative mfeThreshold`() {
        assertThatThrownBy { build(threshold = "-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `withStrategyId preserves all fields and updates strategyId`() {
        val s = build()
        val stamped = s.withStrategyId("alpha") as OrderRequest.ArmedTrailingStop
        assertThat(stamped.strategyId).isEqualTo("alpha")
        assertThat(stamped.entryPrice).isEqualByComparingTo("100")
        assertThat(stamped.trailDistance).isEqualByComparingTo("5")
        assertThat(stamped.mfeThreshold).isEqualByComparingTo("10")
    }

    @Test
    fun `withExpiresAt sets expiresAt`() {
        val s = build()
        val stamped = s.withExpiresAt(1700000000000L) as OrderRequest.ArmedTrailingStop
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
    }
}
