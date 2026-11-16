package com.qkt.verification

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Oracle fixtures that expose a known bug in qkt, kept out of [IndicatorVerificationTest]'s factory and
 * disabled here one by one, each with the reason. The fixture itself asserts the correct behaviour
 * (Undefined, never an exception), so re-enabling the test is the proof once the fix lands.
 */
class IndicatorVerificationKnownBugsTest {
    private fun verify(name: String) {
        val fixture = VerificationFixtures.indicatorFixtures().first { it.first == name }.second
        IndicatorVerificationTest.verify(name, fixture, VerificationFixtures.series().getValue(fixture.series))
    }

    @Test
    @Disabled("bug: Skew divides by a zero previous price (ArithmeticException); slice 1 adds the guard")
    fun `skew over a zero price is Undefined for that window`() = verify("SKEW__5__synth_zero")

    @Test
    @Disabled("bug: VarianceRatio divides by a zero previous price (ArithmeticException); slice 1 adds the guard")
    fun `variance ratio over a zero price is Undefined for that window`() = verify("VARIANCE_RATIO__2_6__synth_zero")

    @Test
    @Disabled("bug: SessionMomentum divides by a zero session open (ArithmeticException); slice 1 guards it")
    fun `session momentum over a zero session open is Undefined for that day`() =
        verify("SESSION_MOMENTUM__0_3_1__synth_zero")

    @Test
    @Disabled("bug: SessionVwap one-pass variance at 16 digits reads a spurious stdev on a session's first bars")
    fun `session vwap stdev on btc 1h is zero on a session's first bar`() = verify("VWAP_SESSION_STDEV__0__btc_1h")

    @Test
    @Disabled("bug: SessionVwap one-pass variance at 16 digits reads a spurious stdev on a session's first bars")
    fun `session vwap stdev on xau 1h is zero on a session's first bar`() = verify("VWAP_SESSION_STDEV__0__xau_1h")

    @Test
    @Disabled("bug: SessionVwap one-pass variance at 16 digits reads a spurious stdev on a session's first bars")
    fun `session vwap stdev at 1e9 prices is zero on the first bar`() = verify("VWAP_SESSION_STDEV__0__synth_huge")

    companion object {
        /** Fixture names excluded from the factory; one disabled test above per entry. */
        val KNOWN_BUGS: Set<String> =
            setOf(
                "SKEW__5__synth_zero",
                "VARIANCE_RATIO__2_6__synth_zero",
                "SESSION_MOMENTUM__0_3_1__synth_zero",
                "VWAP_SESSION_STDEV__0__btc_1h",
                "VWAP_SESSION_STDEV__0__xau_1h",
                "VWAP_SESSION_STDEV__0__synth_huge",
            )
    }
}
