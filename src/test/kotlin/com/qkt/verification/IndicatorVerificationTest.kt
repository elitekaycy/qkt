package com.qkt.verification

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every registered indicator and numeric function against the independent oracle in
 * `verification/oracle/indicators` (TA-Lib / numpy / pandas / scipy, see `gen.py`): one dynamic test per
 * fixture, every bar compared, `null` (Undefined) positions included, at the tolerance the fixture states.
 * A fixture that exposes a bug is excluded from the factory and `@Disabled` with the reason in a sibling
 * test class until the fix lands; none is excluded at present.
 */
class IndicatorVerificationTest {
    @TestFactory
    fun `every indicator fixture matches the oracle`(): List<DynamicTest> {
        val series = VerificationFixtures.series()
        return VerificationFixtures
            .indicatorFixtures()
            .map { (name, fixture) -> dynamicTest(name) { verify(name, fixture, series.getValue(fixture.series)) } }
    }

    @TestFactory
    fun `every numeric function case matches the oracle`(): List<DynamicTest> =
        VerificationFixtures.functionFixtures().flatMap { fixture ->
            fixture.cases.map { case ->
                dynamicTest("${fixture.function}(${case.args.joinToString()})") {
                    val actual = OracleReplay.invoke(fixture.function, case.args)
                    val expected = case.expected
                    if (expected == null) {
                        assertThat(actual).describedAs("${fixture.function}(${case.args}) is a domain error").isNull()
                    } else {
                        assertThat(actual).describedAs("${fixture.function}(${case.args}) is defined").isNotNull()
                        val mismatch = compare(expected.toDouble(), actual!!, fixture.tolerance)
                        assertThat(mismatch)
                            .describedAs("${fixture.function}(${case.args}): expected $expected got $actual")
                            .isNull()
                    }
                }
            }
        }

    companion object {
        private const val SHOWN = 8

        /** Compares a replay against its fixture and fails naming the first mismatching bars. */
        fun verify(
            name: String,
            fixture: IndicatorFixture,
            series: OracleSeries,
        ) {
            val actual = OracleReplay.run(fixture, series)
            assertThat(actual).describedAs("$name: one output per bar").hasSize(fixture.values.size)
            val mismatches =
                fixture.values.indices.mapNotNull { i ->
                    val exp = fixture.values[i]
                    val act = actual[i]
                    when {
                        exp == null && act == null -> null
                        exp == null -> "bar $i: expected Undefined, got ${act?.toPlainString()}"
                        act == null -> "bar $i: expected $exp, got Undefined"
                        else -> compare(exp, act, fixture.tolerance)?.let { "bar $i: $it" }
                    }
                }
            assertThat(mismatches)
                .describedAs(
                    "$name (${fixture.oracle}; ${fixture.note}): ${mismatches.size} of ${fixture.values.size} " +
                        "bars differ, first: ${mismatches.take(SHOWN).joinToString("; ")}",
                ).isEmpty()
        }

        /** Null when [actual] is within [tolerance] of [expected], else a description of the miss. */
        fun compare(
            expected: Double,
            actual: BigDecimal,
            tolerance: Tolerance,
        ): String? {
            val a = actual.toDouble()
            val ok = if (tolerance.abs == 0.0 && tolerance.rel == 0.0) a == expected else tolerance.admits(expected, a)
            return if (ok) null else "expected $expected, got ${actual.toPlainString()} (diff ${a - expected})"
        }
    }
}
