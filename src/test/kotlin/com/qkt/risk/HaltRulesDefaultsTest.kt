package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.risk.rules.BookOwnDailyLoss
import com.qkt.risk.rules.DaemonDailyLoss
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HaltRulesDefaultsTest {
    private val scope = DaemonRiskScope(FixedClock(0L))

    @Test
    fun `a daemon-wide set with only a daily loss carries just the daemon daily loss rule`() {
        val rules = HaltRules.daemonWide("s1", scope, BigDecimal("50"))

        assertThat(rules).singleElement().isInstanceOf(DaemonDailyLoss::class.java)
    }

    @Test
    fun `a book set with only a daily loss carries just the book's own daily loss rule`() {
        val rules = HaltRules.book(scope, BigDecimal("50"))

        assertThat(rules).singleElement().isInstanceOf(BookOwnDailyLoss::class.java)
    }

    @Test
    fun `a book set without a daily loss is empty`() {
        assertThat(HaltRules.book(scope, BigDecimal.ZERO)).isEmpty()
    }
}
