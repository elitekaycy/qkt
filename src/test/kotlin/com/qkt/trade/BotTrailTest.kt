package com.qkt.trade

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.observe.insights.InsightsConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BotTrailTest {
    @TempDir
    lateinit var tmp: Path

    private val action =
        parseBotStrategy(
            renderBotStrategy(
                BotIntent(side = Side.BUY, qktSymbol = "EXNESS:XAUUSD", lots = BigDecimal("0.5")),
            ),
        )

    private val order =
        CompiledBotOrder(
            request =
                OrderRequest.Market(
                    id = "bot-1",
                    symbol = "EXNESS:XAUUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("0.5"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1_000L,
                    strategyId = "claude",
                ),
            stopLoss = BigDecimal("2620.50"),
            takeProfit = null,
        )

    @Test
    fun `journals command submit and result under the as name`() {
        val clock = FixedClock(86_400_000L)
        BotTrail(tmp, InsightsConfig.DISABLED, clock).use { trail ->
            trail.recordCommand("claude", action, listOf("bot", "buy", "0.5", "EXNESS:XAUUSD"))
            trail.recordSubmit("claude", order)
            trail.recordResult(
                "claude",
                order,
                BotPlaceResult(
                    ok = true,
                    retcode = 10009,
                    ticket = 123,
                    deal = 456,
                    price = BigDecimal("2650.5"),
                    error = null,
                ),
            )
        }
        val file = tmp.resolve("bot/claude/journal-1970-01-02.jsonl")
        assertThat(file).exists()
        val lines = Files.readAllLines(file)
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).contains("\"kind\":\"bot.command\"").contains(action.sha256)
        assertThat(lines[1]).contains("\"kind\":\"bot.submit\"").contains("\"sl\":\"2620.50\"")
        assertThat(lines[2]).contains("\"kind\":\"bot.accepted\"").contains("\"ticket\":\"123\"")
    }

    @Test
    fun `command envelope carries canonical source and version`() {
        val env = botCommandEnvelope("claude", action, listOf("bot", "buy"), ts = 5L, seq = 0L)
        assertThat(env.type).isEqualTo("bot.command")
        assertThat(env.strategyId).isEqualTo("claude")
        assertThat(env.payload["source"]).isEqualTo(action.source)
        assertThat(env.payload["sha256"]).isEqualTo(action.sha256)
        assertThat(env.payload["qktVersion"]).isNotNull
    }

    @Test
    fun `result envelope maps rejection to order rejected`() {
        val env =
            botOrderResultEnvelope(
                "claude",
                order,
                BotPlaceResult(
                    ok = false,
                    retcode = 10019,
                    ticket = 0,
                    deal = 0,
                    price = BigDecimal.ZERO,
                    error = "No money",
                ),
                ts = 5L,
                seq = 2L,
            )
        assertThat(env.type).isEqualTo("order.rejected")
        assertThat(env.payload["reason"]).isEqualTo("No money")
        assertThat(env.payload["retcode"]).isEqualTo(10019)
    }
}
