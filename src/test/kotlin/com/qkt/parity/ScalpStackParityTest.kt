package com.qkt.parity

import com.qkt.dsl.compile.GeneratedStrategyReplay
import com.qkt.parity.ScalpStackCases.cases
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * Deep stacks driven by fast signals -- the scalping shape, held to the same parity contract as
 * everything else.
 *
 * The strategies a scalper actually runs are not the ones the existing parity suites cover. They
 * stack ten, fifteen, twenty layers off one signal instead of three; they take profit at a
 * distance chosen to clear spread and commission rather than at a round number; they run with a
 * protective stop and exit on a rule rather than a target; and they do all of it repeatedly inside
 * a few minutes rather than once over a day. Each of those is a different load on the same
 * machinery: layer triggers competing with a bracket on the same tick, a cancel racing partially
 * filled layers, and re-entry the moment a scalp closes.
 *
 * Two facts about the engine shape every expectation below. A market order signalled on one tick
 * fills on the next, so each tape opens with its entry price twice: once to fire the rule, once to
 * fill the seed at that price. And the two exit paths book differently: the outer bracket applies
 * to every layer at that layer's own fill and closes one leg at a time -- three filled layers
 * stopped out are three SELL fills -- while a rule-driven `CLOSE` nets the whole stack into one
 * exit. Both are what a hedging venue would actually book for that instruction; a strategy that
 * wants a basket-level target expresses it as a rule over `POSITION.x.entry_price`, not a bracket.
 *
 * Every case runs through all four paths the generated replay harness drives -- tick backtest, bar
 * backtest, tick-resolved source, and a live paper session -- so a divergence between how a stack
 * fills on ticks and how it fills on bars fails here rather than in production.
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ScalpStackParityTest {
    @org.junit.jupiter.api.BeforeAll
    fun quietEngineLogs() = QuietEngineLogs.silence()

    @org.junit.jupiter.api.AfterAll
    fun restoreEngineLogs() = QuietEngineLogs.restore()

    @TestFactory
    fun `scalping stacks match ticks bars and live paper`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.id) {
                val path = tempDir.resolve("${case.id}.qkt")
                Files.writeString(path, case.source)

                val result =
                    GeneratedStrategyReplay.assertTickBarAndLiveParity(
                        path = path,
                        closes = case.prices,
                        expectedTradeCount = case.expectedSides.size,
                    )

                assertThat(result.backtest.trades.map { it.side })
                    .containsExactlyElementsOf(case.expectedSides)
                if (case.endsFlat) {
                    assertThat(result.backtest.positions.filter { it.quantity.toBigDecimal().signum() != 0 })
                        .`as`("every leg opened on this tape should have been closed")
                        .isEmpty()
                    assertThat(result.live.positions.filter { it.quantity.toBigDecimal().signum() != 0 }).isEmpty()
                }
            }
        }
}
