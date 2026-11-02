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

    @Test
    fun `the effective config rides the first start at its sha and its own omission marker after`() {
        val ledger = StrategySourceLedger()

        fun start(sha: String) =
            InsightsTranslate.strategyStarted(
                "gold",
                1L,
                mapOf("effectiveConfigSha256" to sha, "effectiveConfig" to mapOf("schema" to 1)),
            )

        assertThat(ledger.admit(start("aa")).payload).containsKey("effectiveConfig")
        val repeat = ledger.admit(start("aa")).payload
        assertThat(repeat).doesNotContainKey("effectiveConfig")
        assertThat(repeat).containsEntry("effectiveConfigOmitted", true)
        assertThat(repeat).containsEntry("effectiveConfigSha256", "aa")
        assertThat(ledger.admit(start("bb")).payload).containsKey("effectiveConfig")
    }

    @Test
    fun `source and config are kept once each, independently`() {
        val ledger = StrategySourceLedger()

        fun start(config: String) =
            InsightsTranslate.strategyStarted(
                "gold",
                1L,
                mapOf(
                    "sourceSha256" to "src",
                    "sourceText" to "STRATEGY s VERSION 1",
                    "effectiveConfigSha256" to config,
                    "effectiveConfig" to mapOf("schema" to 1),
                ),
            )

        ledger.admit(start("aa"))
        val configChanged = ledger.admit(start("bb")).payload
        assertThat(configChanged).doesNotContainKey("sourceText")
        assertThat(configChanged).containsKey("effectiveConfig")
    }
}
