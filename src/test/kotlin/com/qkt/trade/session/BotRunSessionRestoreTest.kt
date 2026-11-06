package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A session resumed over an earlier run's journal: last fates restored, new intents numbered after them. */
class BotRunSessionRestoreTest {
    @TempDir
    lateinit var tmp: Path

    private val source =
        "STRATEGY bot VERSION 1\n\nSYMBOLS\n    x = EXNESS:XAUUSD EVERY 1m\n\nRULES\n" +
            "    WHEN true\n    THEN BUY x SIZING 0.01\n"

    private object Idle : BotRunBackend {
        override fun awaitNextBar(
            symbol: String,
            before: Long,
            history: BarHistory,
        ) = false

        override fun nowMs() = 0L

        override fun equity(): BigDecimal? = null

        override fun positions() = emptyMap<String, com.qkt.positions.Position>()

        override fun finish(): BacktestResult? = null

        override fun awaitFlat(
            timeoutMs: Long,
            pending: () -> Boolean,
        ) = Unit
    }

    private fun session(journal: IntentJournal? = null): BotRunSession {
        val history = BarHistory(10)
        val bridge = testBridge(history = history)
        val recorder = BotSessionRecorder(history)
        return BotRunSession("r", Idle, mapOf("brain" to bridge), testStreams(), history, recorder, journal)
    }

    private fun journaled(
        id: String,
        fate: IntentFate?,
    ) = JournaledIntent(id, "brain", source, 0L, 0L, fate)

    @Test
    fun `intents that never fired are skipped and the rest keep their last fate`() {
        val journal = IntentJournal(tmp.resolve("intents.jsonl"))
        val s = session(journal)

        s.restoreFates(
            listOf(
                journaled("int-1", null),
                journaled("int-2", IntentFate("int-2", "brain", "queued")),
                journaled("int-7", IntentFate("int-7", "brain", "filled", atMs = 3L)),
            ),
        )

        val fates = s.fates().associateBy { it.intentId }
        assertThat(fates.getValue("int-1").state).isEqualTo("skipped")
        assertThat(fates.getValue("int-1").reason).isEqualTo("the session restarted before it fired")
        assertThat(fates.getValue("int-2").state).isEqualTo("skipped")
        assertThat(fates.getValue("int-7").state).isEqualTo("filled")
        assertThat(s.submit("brain", source).intentId).isEqualTo("int-8")
        assertThat(Files.readAllLines(journal.path).filter { it.contains("\"type\":\"fate\"") }).hasSize(4)
    }

    @Test
    fun `restoring nothing leaves numbering at the start`() {
        val s = session()

        s.restoreFates(emptyList())

        assertThat(s.fates()).isEmpty()
        assertThat(s.submit("brain", source).intentId).isEqualTo("int-1")
        assertThat(s.next("EXNESS:XAUUSD")).isNull()
        assertThat(s.finish()).isNull()
    }
}
