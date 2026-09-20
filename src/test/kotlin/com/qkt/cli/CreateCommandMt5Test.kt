package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateCommandMt5Test : CreateCommandFixture() {
    @Test
    fun `default kind mt5 scaffolds the full stack at the target path`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, stdout, _) = invoke("create", "template", target.toString())
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("Created")

        for (entry in MT5_EXPECTED_FILES) {
            assertThat(target.resolve(entry))
                .withFailMessage("expected $entry at $target")
                .exists()
        }
        assertThat(target.resolve("strategies/ema_cross.qkt")).doesNotExist()
        assertThat(target.resolve("strategies/full_strategy.qkt")).doesNotExist()
        val makefile = Files.readString(target.resolve("Makefile"))
        assertThat(makefile).contains("preflight")
        assertThat(makefile).contains("approve-all")
        assertThat(makefile).contains("deploy: approve")
        assertThat(makefile).contains("resync-dry-run")
        assertThat(makefile).contains("verify-live")
        assertThat(makefile).contains("qkt resync /strategies/$(STRAT).qkt --as $(STRAT)")
        assertThat(makefile).contains("qkt reconcile $(STRAT)")
        val compose = Files.readString(target.resolve("docker-compose.yml"))
        assertThat(compose).contains("stop_grace_period: 30s")
        assertThat(compose).contains("/deploy-scripts/verify-live.sh")
        assertThat(compose).contains("qkt-insights")
        assertThat(compose).contains("ghcr.io/elitekaycy/qkt-insights:latest")
        assertThat(compose).contains("\${QKT_INSIGHTS_BIND_HOST:-127.0.0.1}:\${QKT_INSIGHTS_HOST_PORT:-8420}:8420")
        assertThat(compose).contains("INSIGHTS_NAME: \${QKT_INSIGHTS_INSTANCE_ID:-}")
        assertThat(compose).contains("\"url\":\"http://mt5-gateway:5001/health\"")
        assertThat(compose).contains("\"expect\":{\"mt5_status\":\"connected\"}")
        assertThat(compose).contains("\"headers\":{\"Authorization\":\"Bearer \${MT5_API_KEY}\"}")
        assertThat(compose).contains("DEADMAN_URL: \${QKT_INSIGHTS_DEADMAN_URL:-}")
        assertThat(compose).contains("\${QKT_BIND_HOST:-127.0.0.1}:\${MT5_API_HOST_PORT:-5020}:5001")
        assertThat(compose).doesNotContain("container_name:")
        val topReadme = Files.readString(target.resolve("README.md"))
        assertThat(topReadme).contains("VPS Setup")
        assertThat(topReadme).contains("make preflight STRAT=ema_cross")
        assertThat(topReadme).contains("make resync-dry-run STRAT=ema_cross")
        assertThat(topReadme).contains("QKT_INSIGHTS_ENABLED")
        assertThat(topReadme).contains("CONFIG.md")
        assertThat(Files.readString(target.resolve("CONFIG.md")))
            .contains("Configuration Guide")
            .contains("QKT_MAX_DAILY_LOSS")
            .contains("docker-compose.yml")
        val readme = Files.readString(target.resolve("strategies/README.md"))
        assertThat(readme).contains("Only reviewed, live-ready")
        assertThat(readme).contains("make resync STRAT=ema_cross")
        assertThat(Files.readString(target.resolve("README.md")))
            .contains("qkt MT5 deployment")
            .contains("```dotenv")
        assertThat(Files.readString(target.resolve("scripts/verify-live.sh")))
            .contains("qkt status --deep")
            .contains("expected strategy")
        assertThat(Files.readString(target.resolve("scripts/approve-promotions.sh")))
            .contains("qkt promotion approve")
            .contains("QKT_PROMOTION_ACTOR")
            .contains("QKT_PROMOTION_REASON")
    }

    @Test
    fun `env example pins QKT_IMAGE_TAG to the running version`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        invoke("create", "template", target.toString())
        val envContent = Files.readString(target.resolve(".env.example"))
        assertThat(envContent).contains("QKT_IMAGE_TAG=v${BuildInfo.VERSION}")
        assertThat(envContent).contains("MT5_GATEWAY_IMAGE=elitekaycy/mt5-gateway-api:0.3.5")
        assertThat(envContent).contains("Required: headless MT5 login")
        assertThat(envContent).contains("Usually keep defaults")
        assertThat(envContent).contains("Diagnostic fallback only")
        assertThat(envContent).contains("MT5_ENABLE_ALGO_TRADING=1")
        assertThat(envContent).contains("MT5_API_KEY=replace-with-a-long-random-value")
        assertThat(envContent).contains("QKT_STARTING_BALANCE=50000")
        assertThat(envContent).contains("QKT_MAX_DAILY_LOSS=100")
        assertThat(envContent).contains("QKT_MAX_ORDER_NOTIONAL=50000")
        assertThat(envContent).contains("QKT_MAX_DRAWDOWN_PCT=10")
        assertThat(envContent).contains("QKT_MAX_DAILY_DRAWDOWN_PCT=5")
        assertThat(envContent).contains("QKT_MEASURED_USAGE_HOURS=24")
        assertThat(envContent).contains("COMPOSE_PROJECT_NAME=qkt-mt5")
        assertThat(envContent).contains("MT5_API_HOST_PORT=5020")
        assertThat(envContent).contains("MT5_VNC_HOST_PORT=3020")
        assertThat(envContent).contains("QKT_ALERTS_WAIVER_REASON=")
        assertThat(envContent).contains("QKT_INSIGHTS_ENABLED=false")
        assertThat(envContent).contains("QKT_INSIGHTS_BIND_HOST=127.0.0.1")
        assertThat(envContent).contains("QKT_INSIGHTS_HOST_PORT=8420")
        assertThat(envContent).contains("COMPOSE_PROFILES=")
        assertThat(envContent).contains("QKT_INSIGHTS_IMAGE=ghcr.io/elitekaycy/qkt-insights:latest")
        assertThat(envContent).contains("QKT_INSIGHTS_ALERT_WEBHOOK_URL=")
        assertThat(envContent).contains("QKT_INSIGHTS_DEADMAN_URL=")
    }

    @Test
    fun `mt5 config uses the current authenticated gateway contract`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        invoke("create", "template", target.toString())
        val config = Files.readString(target.resolve("qkt.config.yaml"))
        assertThat(config).contains("mode: production")
        assertThat(config).contains("source: local")
        assertThat(config).contains("QKT_ALERTS_WAIVER_REASON")
        assertThat(config).contains("starting_balance: \${QKT_STARTING_BALANCE:-50000}")
        assertThat(config).contains("max_daily_loss: \${QKT_MAX_DAILY_LOSS}")
        assertThat(config).contains("max_order_notional: \${QKT_MAX_ORDER_NOTIONAL}")
        assertThat(config).contains("max_drawdown_pct: \${QKT_MAX_DRAWDOWN_PCT:-10}")
        assertThat(config).contains("max_daily_drawdown_pct: \${QKT_MAX_DAILY_DRAWDOWN_PCT:-5}")
        assertThat(config).contains("measured_usage_hours: \${QKT_MEASURED_USAGE_HOURS:-24}")
        assertThat(config).contains("type: mt5")
        assertThat(config).contains(
            "gateway_url: \${QKT_BROKER_GATEWAY_URL:-http://mt5-gateway:5001}",
        )
        assertThat(config).contains("api_key: \${QKT_BROKER_API_KEY}")
        assertThat(config).contains("server_time_zone: \${QKT_BROKER_SERVER_TIME_ZONE}")
        assertThat(config).contains("symbol_suffix: \${QKT_BROKER_SYMBOL_SUFFIX:-}")
        assertThat(config).contains("magic: \${QKT_BROKER_MAGIC:-10001}")
        assertThat(config).doesNotContain("exness", "EXNESS")
        assertThat(config).contains("insights:")
        assertThat(config).contains("enabled: \${QKT_INSIGHTS_ENABLED}")
        assertThat(config).contains("url: http://qkt-insights:8420/ingest")
        assertThat(config).contains("journal_dir: /var/lib/qkt/insights-journal")
        assertThat(config).doesNotContain("kind: mt5")
        assertThat(config).doesNotContain("botToken:")

        val compose = Files.readString(target.resolve("docker-compose.yml"))
        assertThat(compose).contains("QKT_STARTING_BALANCE: \${QKT_STARTING_BALANCE")
        assertThat(compose).contains("QKT_MAX_DAILY_LOSS: \${QKT_MAX_DAILY_LOSS")
        assertThat(compose).contains("QKT_MAX_ORDER_QTY: \${QKT_MAX_ORDER_QTY")
        assertThat(compose).contains("QKT_MAX_ORDER_NOTIONAL: \${QKT_MAX_ORDER_NOTIONAL")
        assertThat(compose).contains("QKT_PRICE_COLLAR_PCT: \${QKT_PRICE_COLLAR_PCT")
        assertThat(compose).contains("QKT_MAX_DRAWDOWN_PCT: \${QKT_MAX_DRAWDOWN_PCT")
        assertThat(compose).contains("QKT_MAX_DAILY_DRAWDOWN_PCT: \${QKT_MAX_DAILY_DRAWDOWN_PCT")
        assertThat(compose).contains("QKT_MEASURED_USAGE_HOURS: \${QKT_MEASURED_USAGE_HOURS")
        assertThat(compose).contains("QKT_DATA_HOME: /var/lib/qkt/data")
        assertThat(compose).contains("QKT_BROKER_GATEWAY_URL: http://mt5-gateway:5001")
        assertThat(compose).contains("QKT_BROKER_API_KEY: \${MT5_API_KEY}")
    }
}
