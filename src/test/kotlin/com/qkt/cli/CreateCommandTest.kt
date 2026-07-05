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
    }

    @Test
    fun `env example pins QKT_IMAGE_TAG to the running version`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        invoke("create", "template", target.toString())
        val envContent = Files.readString(target.resolve(".env.example"))
        assertThat(envContent).contains("QKT_IMAGE_TAG=v${BuildInfo.VERSION}")
        assertThat(envContent).contains("MT5_GATEWAY_IMAGE=elitekaycy/mt5-gateway-api:0.3.1")
        assertThat(envContent).contains("MT5_API_KEY=replace-with-a-long-random-value")
    }

    @Test
    fun `mt5 config uses the current authenticated gateway contract`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        invoke("create", "template", target.toString())
        val config = Files.readString(target.resolve("qkt.config.yaml"))
        assertThat(config).contains("type: mt5")
        assertThat(config).contains("gateway_url: \${QKT_EXNESS_URL:-http://mt5-gateway:5001}")
        assertThat(config).contains("api_key: \${QKT_BROKER_EXNESS_API_KEY}")
        assertThat(config).doesNotContain("kind: mt5")
        assertThat(config).doesNotContain("botToken:")
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
        assertThat(compose)
            .withFailMessage("minimal compose should not declare mt5-gateway")
            .doesNotContain("mt5-gateway")
        val makefile = Files.readString(target.resolve("Makefile"))
        assertThat(makefile)
            .withFailMessage("minimal Makefile should not declare audit-ticks target")
            .doesNotContain("audit-ticks")
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
            assertThat(target.resolve("data/README.md")).exists()
            strategies.forEach { assertThat(target.resolve(it)).exists() }
            assertThat(Files.readString(target.resolve("qkt.config.yaml"))).contains("source: local")
            if (kind == "portfolio") {
                val portfolio = Files.readString(target.resolve("strategies/portfolio.qkt"))
                assertThat(Parser(Lexer(portfolio).tokenize()).parseFile())
                    .isInstanceOf(ParseResult.Success::class.java)
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
        assertThat(workflow).contains("printf 'QKT_IMAGE_TAG=v%s")
        assertThat(workflow).contains("'${BuildInfo.VERSION}'")
        assertThat(workflow).contains("docker compose --env-file .env config --quiet")
        assertThat(workflow).doesNotContain("replace-with-a-long-random-value")
        assertThat(target.resolve("DEPLOYMENT.md")).exists()
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
                "Makefile",
                "docker-compose.yml",
                "qkt.config.yaml",
                "strategies/README.md",
                "strategies/ema_cross.qkt",
                "strategies/full_strategy.qkt",
            )
        private val MINIMAL_EXPECTED_FILES = MT5_EXPECTED_FILES - "strategies/full_strategy.qkt"
        private val BYBIT_EXPECTED_FILES = MINIMAL_EXPECTED_FILES
    }
}
