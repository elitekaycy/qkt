package com.qkt.trade

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.observe.insights.InsightsConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BotTrailOwnSinkTest {
    @TempDir
    lateinit var tmp: Path

    private val action =
        parseBotStrategy(
            renderBotStrategy(BotIntent(side = Side.BUY, qktSymbol = "EXNESS:XAUUSD", lots = BigDecimal.ONE)),
        )

    // Nothing listens on port 1: every post fails at once, so spooled envelopes stay in the journal.
    private fun insights(journalDir: String) =
        InsightsConfig.DISABLED.copy(
            enabled = true,
            url = "http://127.0.0.1:1/ingest",
            instanceId = "qkt-test",
            token = "t",
            flushIntervalMs = 10L,
            batchSize = 10,
            queueCapacity = 100,
            journalEnabled = true,
            journalDir = journalDir,
        )

    private fun spooled(dir: Path): String =
        Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".jsonl") }.toList().joinToString("\n") { it.readText() }
        }

    @Test
    fun `an enabled insights config builds a sink that journals under the state root by default`() {
        BotTrail(tmp, insights(journalDir = ""), FixedClock(86_400_000L)).use { trail ->
            trail.recordEvent("claude", "bot.close", mapOf("ticket" to "42"))
        }

        val journal = tmp.resolve("bot-insights-journal")
        assertThat(journal).isDirectory()
        assertThat(spooled(journal)).contains("\"type\":\"bot.close\"").contains("\"ticket\":\"42\"")
    }

    @Test
    fun `an explicit journal directory is used for the sink spool`() {
        val explicit = tmp.resolve("elsewhere")

        BotTrail(tmp, insights(journalDir = explicit.toString()), FixedClock(86_400_000L), run = "r9").use { trail ->
            trail.recordCommand("claude", action, listOf("bot", "buy"))
        }

        assertThat(tmp.resolve("bot-insights-journal")).doesNotExist()
        assertThat(spooled(explicit)).contains("\"type\":\"bot.command\"").contains("\"run\":\"r9\"")
    }

    @Test
    fun `a sink without a journal spools nothing to disk`() {
        BotTrail(tmp, insights("").copy(journalEnabled = false), FixedClock(86_400_000L)).use { trail ->
            trail.recordEvent("claude", "bot.cancel", mapOf("ticket" to "7"))
        }

        assertThat(tmp.resolve("bot-insights-journal")).doesNotExist()
        val orderJournal = Files.readString(tmp.resolve("bot/claude/journal-1970-01-02.jsonl"))
        assertThat(orderJournal).contains("\"kind\":\"bot.cancel\"")
    }
}
