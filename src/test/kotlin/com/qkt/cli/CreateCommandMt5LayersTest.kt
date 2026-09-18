package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateCommandMt5LayersTest : CreateCommandFixture() {
    @Test
    fun `mt5-ci adds a production deployment workflow without embedding secrets`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, _, _) = invoke("create", "template", target.toString(), "--kind", "mt5-ci")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        val workflow = Files.readString(target.resolve(".github/workflows/deploy.yml"))
        assertThat(workflow).contains("environment: production")
        assertThat(workflow).contains("\${{ secrets.MT5_PASSWORD }}")
        assertThat(workflow)
            .contains("QKT_IMAGE_TAG: \${{ vars.QKT_IMAGE_TAG || 'v${BuildInfo.VERSION}' }}")
        assertThat(workflow)
            .contains(
                "MT5_GATEWAY_IMAGE: \${{ vars.MT5_GATEWAY_IMAGE || " +
                    "'elitekaycy/mt5-gateway-api:0.3.5' }}",
            )
        assertThat(workflow).contains("printf 'QKT_IMAGE_TAG=%s\\n' \"\$QKT_IMAGE_TAG\"")
        assertThat(workflow).contains("printf 'MT5_GATEWAY_IMAGE=%s\\n' \"\$MT5_GATEWAY_IMAGE\"")
        assertThat(workflow)
            .contains(
                "MT5_VNC_PASSWORD: \${{ secrets.MT5_VNC_PASSWORD || vars.MT5_VNC_PASSWORD || 'changeme' }}",
            )
        assertThat(workflow).doesNotContain("MT5_API_KEY MT5_VNC_PASSWORD")
        assertThat(workflow).contains("QKT_STARTING_BALANCE")
        assertThat(workflow).contains("QKT_MAX_DAILY_LOSS")
        assertThat(workflow).contains("QKT_MAX_ORDER_NOTIONAL")
        assertThat(workflow).contains("QKT_MAX_DRAWDOWN_PCT")
        assertThat(workflow).contains("QKT_MAX_DAILY_DRAWDOWN_PCT")
        assertThat(workflow).contains("QKT_MEASURED_USAGE_HOURS")
        assertThat(workflow).contains("COMPOSE_PROJECT_NAME")
        assertThat(workflow).contains("MT5_API_HOST_PORT")
        assertThat(workflow).contains("QKT_INSIGHTS_HOST_PORT")
        assertThat(workflow).contains("QKT_INSIGHTS_BIND_HOST")
        assertThat(workflow).contains("QKT_ALERTS_WAIVER_REASON")
        assertThat(workflow).contains("TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID")
        assertThat(workflow).contains("QKT_INSIGHTS_ENABLED")
        assertThat(workflow).contains("COMPOSE_PROFILES=%s\\n' 'insights'")
        assertThat(workflow).contains("QKT_INSIGHTS_INGEST_TOKEN")
        assertThat(workflow).contains("docker compose --env-file .env config --quiet")
        assertThat(workflow).contains("Parse, compile, and preflight QKT strategies")
        assertThat(workflow).contains("Offline preflight performs parse/compile/symbol/risk checks")
        assertThat(workflow).contains("qkt parse \"\$target\"")
        assertThat(workflow).contains("qkt preflight \"\$target\"")
        assertThat(workflow).contains("--state-dir /tmp/qkt-preflight-state")
        assertThat(workflow).contains("/deploy-scripts/approve-promotions.sh")
        assertThat(workflow).contains("protected GitHub production deployment \$GITHUB_SHA")
        assertThat(workflow).contains("seq 1 60")
        assertThat(workflow).contains("/deploy-scripts/verify-live.sh")
        assertThat(workflow).doesNotContain("replace-with-a-long-random-value")
        assertThat(target.resolve("DEPLOYMENT.md")).exists()
        val deployment = Files.readString(target.resolve("DEPLOYMENT.md"))
        assertThat(deployment).contains("QKT_STARTING_BALANCE")
        assertThat(deployment).contains("QKT_MAX_DAILY_LOSS")
        assertThat(deployment).contains("QKT_MAX_DRAWDOWN_PCT")
        assertThat(deployment).contains("QKT_MAX_DAILY_DRAWDOWN_PCT")
        assertThat(deployment).contains("QKT_MEASURED_USAGE_HOURS")
        assertThat(deployment).contains("MT5_API_HOST_PORT")
        assertThat(deployment).contains("COMPOSE_PROJECT_NAME")
        assertThat(deployment).contains("QKT_ALERTS_WAIVER_REASON")
        assertThat(deployment).contains("QKT_INSIGHTS_ENABLED")
        assertThat(deployment).contains("MT5 logs")
        assertThat(deployment).contains("make preflight STRAT=<strategy>")
        assertThat(deployment).contains("qkt resync /strategies/<strategy>.qkt")
        assertThat(deployment).contains("verify-live")
    }

    @Test
    fun `kind bot layers ai agent files on the mt5 stack`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, _, _) = invoke("create", "template", target.toString(), "--kind", "bot")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        for (entry in MT5_EXPECTED_FILES) {
            assertThat(target.resolve(entry))
                .withFailMessage("expected mt5 base file $entry at $target")
                .exists()
        }
        val prompt = Files.readString(target.resolve("SYSTEM_PROMPT.md"))
        assertThat(prompt).contains("qkt bot buy")
        assertThat(prompt).contains("--dry-run")
        assertThat(prompt).contains("--as <your-agent-name>")
        assertThat(prompt).contains("qkt ${BuildInfo.VERSION}")
        val bot = Files.readString(target.resolve("BOT.md"))
        assertThat(bot).contains("SYSTEM_PROMPT.md")
        assertThat(bot).contains("qkt bot account --json")
    }
}
