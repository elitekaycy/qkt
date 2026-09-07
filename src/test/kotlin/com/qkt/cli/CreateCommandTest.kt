package com.qkt.cli

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateCommandTest {
    private fun invoke(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = runMain(argv as Array<String>)
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

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

    @Test
    fun `--kind minimal scaffolds the no-broker tree without mt5 gateway`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, _, _) = invoke("create", "template", target.toString(), "--kind", "minimal")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        for (entry in MINIMAL_EXPECTED_FILES) {
            assertThat(target.resolve(entry))
                .withFailMessage("expected $entry at $target")
                .exists()
        }
        val compose = Files.readString(target.resolve("docker-compose.yml"))
        assertThat(compose).contains("stop_grace_period: 30s")
        assertThat(compose)
            .withFailMessage("minimal compose should not declare mt5-gateway")
            .doesNotContain("mt5-gateway")
        val makefile = Files.readString(target.resolve("Makefile"))
        assertThat(makefile)
            .withFailMessage("minimal Makefile should not declare audit-ticks target")
            .doesNotContain("audit-ticks")
        assertThat(makefile).contains("resync-dry-run")
        assertThat(makefile).contains("qkt resync /strategies/$(STRAT).qkt --as $(STRAT)")
        assertThat(makefile).contains("qkt reconcile $(STRAT)")
    }

    @Test
    fun `create refuses to overwrite a non-empty target`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        Files.createDirectories(target)
        val existing = target.resolve("existing.txt")
        Files.writeString(existing, "do not touch")

        val (code, _, stderr) = invoke("create", "template", target.toString())
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("not empty")
        assertThat(Files.readString(existing)).isEqualTo("do not touch")
    }

    @Test
    fun `--kind bybit scaffolds the no-gateway tree wired for Bybit REST`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, _, _) = invoke("create", "template", target.toString(), "--kind", "bybit")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        for (entry in BYBIT_EXPECTED_FILES) {
            assertThat(target.resolve(entry))
                .withFailMessage("expected $entry at $target")
                .exists()
        }
        val compose = Files.readString(target.resolve("docker-compose.yml"))
        assertThat(compose).contains("stop_grace_period: 30s")
        assertThat(compose)
            .withFailMessage("bybit compose should not declare mt5-gateway")
            .doesNotContain("mt5-gateway")
        assertThat(compose)
            .withFailMessage("bybit compose should expose BYBIT_API_KEY env var")
            .contains("BYBIT_API_KEY")
        assertThat(compose)
            .withFailMessage("bybit compose should default BYBIT_TESTNET to true")
            .contains("BYBIT_TESTNET:-true")
        val env = Files.readString(target.resolve(".env.example"))
        assertThat(env).contains("BYBIT_API_KEY=")
        assertThat(env).contains("BYBIT_TESTNET=true")
        val strat = Files.readString(target.resolve("strategies/ema_cross.qkt"))
        assertThat(strat).contains("BYBIT_LINEAR:BTCUSDT")
        val makefile = Files.readString(target.resolve("Makefile"))
        assertThat(makefile).contains("resync-dry-run")
        assertThat(makefile).contains("qkt resync /strategies/$(STRAT).qkt --as $(STRAT)")
        assertThat(makefile).contains("qkt reconcile $(STRAT)")
    }

    @Test
    fun `research kinds scaffold complete backtest projects`(
        @TempDir tmp: Path,
    ) {
        val expectedStrategies =
            mapOf(
                "backtest" to listOf("strategies/backtest.qkt"),
                "portfolio" to
                    listOf(
                        "strategies/portfolio.qkt",
                        "strategies/trend.qkt",
                        "strategies/mean_reversion.qkt",
                    ),
            )
        for ((kind, strategies) in expectedStrategies) {
            val target = tmp.resolve(kind)
            val (code, stdout, _) = invoke("create", "template", target.toString(), "--kind", kind)
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            assertThat(stdout).contains("make backtest")
            assertThat(target.resolve(".gitignore")).exists()
            assertThat(target.resolve("README.md")).exists()
            assertThat(target.resolve("data/README.md")).exists()
            strategies.forEach { assertThat(target.resolve(it)).exists() }
            assertThat(Files.readString(target.resolve("qkt.config.yaml"))).contains("source: local")
            if (kind == "portfolio") {
                val portfolio = Files.readString(target.resolve("strategies/portfolio.qkt"))
                assertThat(Parser(Lexer(portfolio).tokenize()).parseFile())
                    .isInstanceOf(ParseResult.Success::class.java)
                val makefile = Files.readString(target.resolve("Makefile"))
                assertThat(makefile).contains("qkt resync /strategies/portfolio.qkt --as $(BOOK)")
                assertThat(makefile).contains("qkt reconcile $(BOOK)")
            }
        }
    }

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

    @Test
    fun `unknown --kind errors out and lists valid kinds`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, _, stderr) = invoke("create", "template", target.toString(), "--kind", "notathing")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("unknown --kind 'notathing'")
        assertThat(stderr).contains("mt5")
        assertThat(stderr).contains("mt5-ci")
        assertThat(stderr).contains("backtest")
        assertThat(stderr).contains("portfolio")
        assertThat(stderr).contains("minimal")
        assertThat(stderr).contains("bybit")
    }

    @Test
    fun `missing path argument errors out with usage`() {
        val (code, _, stderr) = invoke("create", "template")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("missing required argument")
    }

    @Test
    fun `wrong subcommand under create errors out`() {
        val (code, _, stderr) = invoke("create", "frobnicate")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stderr).contains("usage")
    }

    private companion object {
        private val MT5_EXPECTED_FILES =
            listOf(
                ".env.example",
                ".gitignore",
                "README.md",
                "CONFIG.md",
                "Makefile",
                "docker-compose.yml",
                "qkt.config.yaml",
                "scripts/approve-promotions.sh",
                "scripts/verify-live.sh",
                "examples/strategies/README.md",
                "examples/strategies/ema_cross.qkt",
                "examples/strategies/full_strategy.qkt",
                "strategies/.gitkeep",
                "strategies/README.md",
            )
        private val MINIMAL_EXPECTED_FILES =
            listOf(
                ".env.example",
                ".gitignore",
                "README.md",
                "CONFIG.md",
                "Makefile",
                "docker-compose.yml",
                "qkt.config.yaml",
                "strategies/README.md",
                "strategies/ema_cross.qkt",
            )
        private val BYBIT_EXPECTED_FILES = MINIMAL_EXPECTED_FILES
    }
}
