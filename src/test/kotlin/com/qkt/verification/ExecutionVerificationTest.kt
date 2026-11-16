package com.qkt.verification

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/**
 * Every execution scenario under `verification/oracle/engine/scenarios` run through the real `qkt backtest` path
 * ([ExecutionScenarioRun]) and compared fill by fill with the trade tape the independent Decimal reference engine
 * derived from qkt's docs (`verification/oracle/engine`, see its README for every rule and its doc line).
 *
 * Comparison is exact: side, quantity, price, realised (net of that fill's commission) and the armed stop and
 * target levels compare as decimals with no tolerance; timestamps compare exactly, on the synthetic bar path too
 * (`docs/concepts/backtest-model.md` "Synthetic print times"), unless an expectation states bar granularity.
 * The run's `finalRealized` and `commissionPaid` are compared at the two decimals `--json` rounds them to.
 * Scenarios that expose a bug are held in [ExecutionVerificationKnownBugsTest] instead of here.
 */
class ExecutionVerificationTest {
    @TempDir
    lateinit var tmp: Path

    @TestFactory
    fun `every execution scenario matches the oracle tape`(): List<DynamicTest> =
        ExecutionScenarios
            .scenarios()
            .filterNot { it.name in ExecutionVerificationKnownBugsTest.KNOWN_BUGS }
            .map { scenario ->
                dynamicTest(scenario.name) {
                    val levels = !ExecutionVerificationKnownBugsTest.levelsUnreported(scenario)
                    verify(scenario, Files.createDirectories(tmp.resolve(scenario.name)), levels)
                }
            }

    companion object {
        /** Checks [scenario] against its oracle tape; [levels] false skips the entry rows' reported stop and target. */
        fun verify(
            scenario: ExecutionScenario,
            dir: Path,
            levels: Boolean = true,
        ) {
            val expectation = ExecutionScenarios.expectation(scenario.name)
            val run = ExecutionScenarioRun.run(scenario, dir)
            val about = "${scenario.name} (${scenario.proves})"
            val got = run.fills.joinToString("\n") { it.toTapeLine() }
            assertThat(run.fills).describedAs("$about: fills, got\n$got").hasSize(expectation.trades.size)
            val tick = expectation.timestampGranularity == "tick"
            val expected = if (tick) expectation.trades.sortedWith(EXPECTED_ORDER) else expectation.trades
            val actual = if (tick) run.fills.sortedWith(ACTUAL_ORDER) else run.fills
            val mismatches =
                expected.indices.mapNotNull { i ->
                    compare(i, expected[i], actual[i], expectation.timestampGranularity, scenario, levels)
                }
            assertThat(mismatches).describedAs("$about: ${mismatches.size} fills differ").isEmpty()
            assertThat(run.finalRealized.cents())
                .describedAs("$about: finalRealized")
                .isEqualByComparingTo(BigDecimal(expectation.realized).cents())
            assertThat(run.commissionPaid.cents())
                .describedAs("$about: commissionPaid")
                .isEqualByComparingTo(BigDecimal(expectation.commission).cents())
            assertThat(run.rejections).describedAs("$about: rejections").isEqualTo(expectation.rejections)
            assertThat(run.fills.sumOf { it.realized })
                .describedAs("$about: sum of realized")
                .isEqualByComparingTo(BigDecimal(expectation.realized))
        }

        /**
         * Fills on one tick are compared as a set: the docs fix which orders fill on a tick, not the order the
         * trade log lists two of them in (two legs stopped by one gap, a STACK_AT leg opening as its primary's
         * target fills). Both tapes are put in the same order, by time, then side, reason, quantity and price.
         */
        private val EXPECTED_ORDER: Comparator<ExpectedFill> =
            compareBy({ it.ts }, { it.side }, { it.reason }, { BigDecimal(it.qty) }, { BigDecimal(it.price) })
        private val ACTUAL_ORDER: Comparator<ActualFill> =
            compareBy({ it.ts }, { it.side }, { it.reason }, { it.qty }, { it.price })

        private fun compare(
            i: Int,
            exp: ExpectedFill,
            act: ActualFill,
            granularity: String,
            scenario: ExecutionScenario,
            levels: Boolean,
        ): String? {
            val problems = mutableListOf<String>()
            if (granularity == "tick") {
                if (act.ts != exp.ts) problems += "ts ${act.ts} != ${exp.ts}"
            } else {
                val barStart = checkNotNull(exp.barStart)
                val barEnd = barStart + scenario.timeframeSec * 1000L
                if (act.ts < barStart || act.ts >= barEnd) problems += "ts ${act.ts} outside bar [$barStart, $barEnd)"
            }
            if (act.side != exp.side) problems += "side ${act.side} != ${exp.side}"
            if (act.reason != exp.reason) problems += "reason ${act.reason} != ${exp.reason}"
            differs("qty", act.qty, exp.qty)?.let { problems += it }
            differs("price", act.price, exp.price)?.let { problems += it }
            differs("realized", act.realized, exp.realized)?.let { problems += it }
            if (levels) {
                exp.stopLoss?.let { differs("stopLoss", act.stopLoss, it)?.let { p -> problems += p } }
                exp.takeProfit?.let { differs("takeProfit", act.takeProfit, it)?.let { p -> problems += p } }
            }
            if (problems.isEmpty()) return null
            return "fill $i (${exp.reason} ${exp.side}): ${problems.joinToString(", ")}"
        }

        private fun differs(
            what: String,
            actual: BigDecimal?,
            expected: String,
        ): String? =
            if (actual != null && actual.compareTo(BigDecimal(expected)) == 0) {
                null
            } else {
                "$what ${actual?.toPlainString()} != $expected"
            }

        private fun BigDecimal.cents(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

        private fun ActualFill.toTapeLine(): String {
            val levels = stopLoss?.let { " sl ${it.toPlainString()} tp ${takeProfit?.toPlainString()}" } ?: ""
            return "$ts $side $reason ${qty.toPlainString()} @ ${price.toPlainString()} " +
                "realized ${realized.toPlainString()}$levels"
        }
    }
}
