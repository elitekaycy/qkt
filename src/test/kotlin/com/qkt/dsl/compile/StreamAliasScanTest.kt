package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [streamAliasesIn] lists the streams an expression reads, first-seen order, without duplicates. */
class StreamAliasScanTest {
    private fun f(alias: String) = StreamFieldRef(alias, "close")

    private val one = NumLit(BigDecimal.ONE)

    @Test
    fun `crosses, ranges and membership tests are walked left to right`() {
        assertThat(streamAliasesIn(Crosses(CrossDir.BELOW, f("a"), f("b")))).containsExactly("a", "b")
        assertThat(streamAliasesIn(Between(f("c"), f("a"), f("b")))).containsExactly("c", "a", "b")
        assertThat(streamAliasesIn(InList(f("b"), listOf(one, f("a"), f("b"))))).containsExactly("b", "a")
    }

    @Test
    fun `null tests, aggregates and case branches contribute their streams`() {
        val expr =
            CaseWhen(
                listOf(IsNull(f("x"), negated = true) to Aggregate(AggFn.MAX, f("y"), SinceOpen)),
                BoolLit(false),
            )

        assertThat(streamAliasesIn(expr)).containsExactly("x", "y")
        assertThat(streamAliasesIn(one)).isEmpty()
    }
}
