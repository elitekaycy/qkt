package com.qkt.trade.session

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.Sell
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SessionIntentTest {
    private val gold = "EXNESS:XAUUSD"
    private val streams = BotSessionStreams(listOf("EXNESS:XAGUSD", gold), listOf("1m"))

    private fun source(
        action: String,
        cond: String = "true",
        symbol: String = gold,
    ) = "STRATEGY bot VERSION 1\n\nSYMBOLS\n    g = $symbol EVERY 1m\n\nRULES\n    WHEN $cond\n    THEN $action\n"

    @Test
    fun `an intent's stream is renamed to the session alias of its symbol`() {
        val intent = sessionIntent(source("BUY g SIZING 0.1"), streams)

        assertThat((intent.action as Buy).stream).isEqualTo("s1_1m")
        assertThat(intent.symbol).isEqualTo(gold)
        assertThat(intent.sha256).hasSize(64)
    }

    @Test
    fun `sell and cancel name the symbol they act on`() {
        assertThat((sessionIntent(source("SELL g SIZING 0.1"), streams).action as Sell).stream).isEqualTo("s1_1m")
        val cancel = sessionIntent(source("CANCEL g"), streams)
        assertThat((cancel.action as Cancel).stream).isEqualTo("s1_1m")
        assertThat(cancel.symbol).isEqualTo(gold)
    }

    @Test
    fun `an account wide action targets no single symbol`() {
        val intent = sessionIntent(source("CLOSE_ALL"), streams)

        assertThat(intent.action).isEqualTo(CloseAll)
        assertThat(intent.symbol).isNull()
    }

    @Test
    fun `source that does not parse is refused with the parser's reasons`() {
        assertThatThrownBy { sessionIntent("STRATEGY", streams) }.hasMessageStartingWith("intent failed to parse: ")
    }

    @Test
    fun `an intent holds exactly one rule`() {
        val twoRules = source("BUY g SIZING 0.1") + "    WHEN true\n    THEN SELL g SIZING 0.1\n"

        assertThatThrownBy { sessionIntent(twoRules, streams) }
            .hasMessage("an intent holds exactly one WHEN/THEN rule, got 2")
    }

    @Test
    fun `a symbol the session does not declare is refused`() {
        assertThatThrownBy { sessionIntent(source("BUY g SIZING 0.1", symbol = "EXNESS:EURUSD"), streams) }
            .hasMessage(
                "EXNESS:EURUSD is not a session symbol; the session declares EXNESS:XAGUSD@1m, EXNESS:XAUUSD@1m",
            )
    }

    @Test
    fun `an intent's rule must be unconditional`() {
        assertThatThrownBy { sessionIntent(source("BUY g SIZING 0.1", cond = "false"), streams) }
            .hasMessageStartingWith("an intent's rule is WHEN true; got")
        assertThatThrownBy { sessionIntent(source("BUY g SIZING 0.1", cond = "1 > 2"), streams) }
            .hasMessageStartingWith("an intent's rule is WHEN true; got")
    }
}
