package com.qkt.cli

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateCommandTest : CreateCommandFixture() {
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
    fun `every kind maps qkt files to github syntax highlighting`(
        @TempDir tmp: Path,
    ) {
        for (kind in listOf("mt5", "mt5-ci", "backtest", "portfolio", "minimal", "bybit", "bot")) {
            val target = tmp.resolve(kind)
            val (code, _, _) = invoke("create", "template", target.toString(), "--kind", kind)
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            val attributes = target.resolve(".gitattributes")
            assertThat(attributes).withFailMessage("kind $kind has no .gitattributes").exists()
            assertThat(Files.readAllLines(attributes))
                .contains("*.qkt linguist-language=Haskell linguist-detectable=false")
        }
    }

    @Test
    fun `a gist highlighting modeline on the first line still parses`() {
        val template = javaClass.classLoader.getResourceAsStream("templates/minimal/strategies/ema_cross.qkt")
        val strategy = "-- -*- mode: haskell -*-\n" + String(requireNotNull(template).readBytes())
        assertThat(Parser(Lexer(strategy).tokenize()).parseFile()).isInstanceOf(ParseResult.Success::class.java)
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
}
