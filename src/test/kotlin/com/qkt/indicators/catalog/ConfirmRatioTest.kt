package com.qkt.indicators.catalog

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ConfirmRatioTest {
    private fun row(vararg v: String) = v.map { BigDecimal(it) }

    @Test
    fun `null before warmup`() {
        val cr = ConfirmRatio(period = 2, peerCount = 2)
        cr.update(row("10", "100", "50"))
        cr.update(row("11", "101", "49"))
        // Needs period+1 = 3 aligned bars.
        assertThat(cr.isReady).isFalse()
        assertThat(cr.value()).isNull()
    }

    @Test
    fun `ratio is the fraction of peers whose return matches the signal direction`() {
        val cr = ConfirmRatio(period = 2, peerCount = 2)
        cr.update(row("10", "100", "50"))
        cr.update(row("11", "101", "49"))
        cr.update(row("12", "102", "48"))
        // over the window: signal +2 (up); peer1 +2 (up, confirms); peer2 -2 (down, no) → 1/2.
        assertThat(cr.value()).isEqualByComparingTo("0.5")
    }

    @Test
    fun `all peers confirming gives one`() {
        val cr = ConfirmRatio(period = 1, peerCount = 2)
        cr.update(row("10", "100", "50"))
        cr.update(row("11", "101", "51"))
        assertThat(cr.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `no peers confirming gives zero`() {
        val cr = ConfirmRatio(period = 1, peerCount = 2)
        cr.update(row("10", "100", "50"))
        cr.update(row("11", "99", "49"))
        assertThat(cr.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `a negated peer confirms when the underlying moves opposite (polarity)`() {
        // Caller passes -usdchf.close as the peer; here we feed its already-negated values.
        // Signal up; the negated peer also rises → it confirms.
        val cr = ConfirmRatio(period = 1, peerCount = 1)
        cr.update(row("10", "-100"))
        cr.update(row("11", "-99"))
        assertThat(cr.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `window rolls forward`() {
        val cr = ConfirmRatio(period = 1, peerCount = 1)
        cr.update(row("10", "100"))
        cr.update(row("11", "99")) // signal up, peer down → 0
        cr.update(row("12", "101")) // window [11,99],[12,101]: signal up, peer up → 1
        assertThat(cr.value()).isEqualByComparingTo("1")
    }

    @Test
    fun `rejects fewer than one peer`() {
        assertThatThrownBy { ConfirmRatio(period = 2, peerCount = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects non-positive period`() {
        assertThatThrownBy { ConfirmRatio(period = 0, peerCount = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
