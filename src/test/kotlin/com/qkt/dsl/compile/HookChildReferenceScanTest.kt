package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.ExitHooksAst
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenThen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The stream and instrument-meta scans walk LOG fields of ON_FILL and exit-hook children and skip other children. */
class HookChildReferenceScanTest {
    private fun log(field: StreamFieldRef) = Log(LogLevel.INFO, "x", linkedMapOf("v" to field))

    private val rule =
        WhenThen(
            BoolLit(true),
            Buy(
                "gold",
                ActionOpts(
                    sizing = SizeQty(NumLit(BigDecimal.ONE)),
                    onFill = listOf(log(StreamFieldRef("silver", "tick_size")), Close("gold")),
                    exitHooks =
                        ExitHooksAst(
                            onClose = listOf(log(StreamFieldRef("oil", "contract_size")), Close("gold")),
                        ),
                ),
            ),
        )

    @Test
    fun `streams read by hook and ON_FILL log fields gate the rule`() {
        assertThat(collectStreamAliases(rule)).containsExactlyInAnyOrder("gold", "silver", "oil")
    }

    @Test
    fun `meta fields read by hook and ON_FILL log fields are collected`() {
        val streams = listOf("gold", "silver", "oil").associateWith { HubKey("BACKTEST", it.uppercase(), "1m") }
        val ast =
            StrategyAst(
                "s",
                1,
                streams.keys.map { StreamDecl(it, "BACKTEST", it.uppercase(), "1m") },
                emptyList(),
                emptyList(),
                defaults = null,
                rules = listOf(rule),
            )

        assertThat(collectMetaRefs(ast, streams))
            .containsExactlyInAnyOrder(
                MetaRef("silver", "tick_size", "BACKTEST:SILVER"),
                MetaRef("oil", "contract_size", "BACKTEST:OIL"),
            )
    }
}
