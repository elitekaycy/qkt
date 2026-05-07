package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotTPast
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SnapshotAggregateBuildersTest {
    @Test
    fun `at atOpen builds Ref with SnapshotOpen`() {
        val r = Ref("fast")
        assertThat(r at atOpen).isEqualTo(Ref("fast", SnapshotOpen))
    }

    @Test
    fun `at atT(n) builds Ref with SnapshotTPast`() {
        val r = Ref("fast")
        assertThat(r at atT(3)).isEqualTo(Ref("fast", SnapshotTPast(3)))
    }

    @Test
    fun `at on non-Ref throws`() {
        assertThatThrownBy { 1.bd at atOpen }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `runMax with sinceOpen builds Aggregate`() {
        assertThat(runMax(1.bd, sinceOpen))
            .isEqualTo(Aggregate(AggFn.MAX, NumLit(1.toBigDecimal()), SinceOpen))
    }

    @Test
    fun `runMean with sinceT builds Aggregate with SinceTPast`() {
        assertThat(runMean(1.bd, sinceT(20)))
            .isEqualTo(Aggregate(AggFn.MEAN, NumLit(1.toBigDecimal()), SinceTPast(20)))
    }
}
