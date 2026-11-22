package com.qkt.dsl.sets

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.universe.SetMembership
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A set member's order ids never repeat: not when it rejoins during a run, not after a restart. A venue gateway is
 * idempotent on the client order id, so a reused id would swallow the new order (an entry, an exit or a stop).
 */
class SetMemberOrderIdsTest {
    private val ast =
        (
            Dsl.parse(
                """
                STRATEGY movers VERSION 1
                SYMBOLS
                    movers = BACKTEST:* WHERE kind = spot TOP 3 BY symbol EVERY 1m
                RULES
                    FOR EACH m IN movers DO
                      WHEN m.close > sma(m.close, 3) THEN BUY m SIZING 1
                """.trimIndent(),
            ) as ParseResult.Success
        ).value

    private val prefix = "dsl-movers.movers.AAA-"
    private val none: (HubKey, Int, Long) -> List<Candle> = { _, _, _ -> emptyList() }
    private val membership = SetMembership()

    private fun instances(
        ids: Collection<String> = emptyList(),
        marks: Map<String, Long> = emptyMap(),
    ) = SetMemberInstances(
        SetMemberTemplate(ast, "movers"),
        CandleHub(),
        testStrategyContext(strategyId = "movers"),
        emit = {},
        resumedIds = { ids },
        restoredMarks = { marks },
        warmup = none,
    )

    @Test
    fun `a member that rejoins continues the order ids it sent before it left`() {
        val instances = instances()
        instances.apply(membership.step(listOf("AAA"), emptySet()))
        val first = instances.compiled.single().orderIds
        assertThat(listOf(first?.next(), first?.next())).containsExactly("$prefix-0", "$prefix-1")
        instances.apply(membership.step(emptyList(), emptySet()))
        assertThat(instances.idMarks()).containsEntry(prefix, 1L)
        instances.apply(membership.step(listOf("AAA"), emptySet()))
        assertThat(instances.compiled.single().orderIds?.next()).isEqualTo("$prefix-2")
        assertThat(instances.idMarks()).containsEntry(prefix, 2L)
    }

    @Test
    fun `a member continues past the ids and the marks a restart restored`() {
        val byIds = instances(ids = listOf("$prefix-5", "ORD-x-9"))
        byIds.apply(membership.step(listOf("AAA"), emptySet()))
        assertThat(byIds.compiled.single().orderIds?.next()).isEqualTo("$prefix-6")
        val byMarks = instances(marks = mapOf(prefix to 11L, "dsl-other-" to 50L))
        byMarks.apply(SetMembership().step(listOf("AAA"), emptySet()))
        assertThat(byMarks.compiled.single().orderIds?.next()).isEqualTo("$prefix-12")
    }
}
