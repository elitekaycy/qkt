package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategySourceLedgerTest {
    private fun started(
        strategyId: String,
        sha: String?,
        text: String? = "STRATEGY s VERSION 1",
        ts: Long = 1L,
    ) = InsightsTranslate.strategyStarted(
        strategyId,
        ts,
        mapOf("sourceSha256" to sha, "sourceText" to text, "sourceTruncated" to false),
    )

    @Test
    fun `the first start of a strategy at a sha carries its text`() {
        val ledger = StrategySourceLedger()

        val out = ledger.admit(started("gold", "aa"))

        assertThat(out.payload["sourceText"]).isEqualTo("STRATEGY s VERSION 1")
        assertThat(out.payload).doesNotContainKey("sourceTextOmitted")
    }

    @Test
    fun `a later start at the same sha keeps the sha and leaves the text out`() {
        val ledger = StrategySourceLedger()
        ledger.admit(started("gold", "aa"))

        val again = ledger.admit(started("gold", "aa", ts = 2L))

        assertThat(again.payload["sourceSha256"]).isEqualTo("aa")
        assertThat(again.payload).doesNotContainKeys("sourceText", "sourceTruncated")
        assertThat(again.payload["sourceTextOmitted"]).isEqualTo(true)
        assertThat(again.toJson("i")).doesNotContain("STRATEGY s VERSION 1")
    }

    @Test
    fun `a changed sha, or another strategy at the same sha, sends the text again`() {
        val ledger = StrategySourceLedger()
        ledger.admit(started("gold", "aa"))

        assertThat(ledger.admit(started("gold", "bb")).payload["sourceText"]).isNotNull
        assertThat(ledger.admit(started("book:b", "aa")).payload["sourceText"]).isNotNull
    }

    @Test
    fun `envelopes without source text pass unchanged`() {
        val ledger = StrategySourceLedger()
        val noText = started("gold", "aa", text = null)
        val noSha = started("gold", null)
        val stopped = InsightsTranslate.strategyStopped("gold", 3L, flatten = false)

        assertThat(ledger.admit(noText)).isSameAs(noText)
        assertThat(ledger.admit(noSha)).isSameAs(noSha)
        assertThat(ledger.admit(stopped)).isSameAs(stopped)
        assertThat(ledger.admit(started("gold", "aa")).payload["sourceText"]).isNotNull
    }
}
