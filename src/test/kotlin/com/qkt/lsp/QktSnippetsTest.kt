package com.qkt.lsp

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QktSnippetsTest {
    @Test
    fun `every snippet is well formed with a unique prefix and title`() {
        assertThat(QktSnippets.all).isNotEmpty
        for (s in QktSnippets.all) {
            assertThat(s.title).withFailMessage("blank title in %s", s).isNotBlank()
            assertThat(s.prefix).withFailMessage("blank prefix in %s", s).isNotBlank()
            assertThat(s.description).withFailMessage("blank description in %s", s).isNotBlank()
            assertThat(s.body).withFailMessage("empty body in %s", s).isNotEmpty()
        }
        assertThat(QktSnippets.all.map { it.prefix }).doesNotHaveDuplicates()
        assertThat(QktSnippets.all.map { it.title }).doesNotHaveDuplicates()
    }

    @Test
    fun `the complete strategy snippets parse with their default placeholders`() {
        // The base "strategy" skeleton resolves to placeholder words (WHEN condition THEN action)
        // and is not meant to parse; the full and complete templates must be real, runnable DSL.
        for (prefix in listOf("stratfull", "strat-ema")) {
            val snippet = QktSnippets.all.first { it.prefix == prefix }
            val src = render(snippet.body)
            val analysis = DiagnosticsRunner.analyze(src)
            assertThat(analysis.diagnostics)
                .withFailMessage(
                    "snippet '%s' does not parse:%n%s%nerrors: %s",
                    prefix,
                    src,
                    analysis.diagnostics.map { it.message },
                ).isEmpty()
            assertThat(analysis.parsed).withFailMessage("snippet '%s' produced no AST", prefix).isNotNull()
        }
    }

    @Test
    fun `vscode snippets file is generated from QktSnippets`() {
        val file = snippetsFile()
        val expected = QktSnippets.toVscodeJson()
        // Bootstrap/refresh the committed file from the single source when explicitly asked:
        //   QKT_SNIPPETS_REGEN=1 ./gradlew test --tests '*QktSnippetsTest*' --no-daemon
        if (System.getenv("QKT_SNIPPETS_REGEN") != null) {
            Files.writeString(file, expected + "\n")
        }
        assertThat(Files.readString(file).trimEnd())
            .withFailMessage(
                "editor/vscode/snippets/qkt.json is out of sync with QktSnippets. Regenerate with: " +
                    "QKT_SNIPPETS_REGEN=1 ./gradlew test --tests '*QktSnippetsTest*' --no-daemon",
            ).isEqualTo(expected.trimEnd())
    }

    /** Locate `editor/vscode/snippets/qkt.json` by walking up from the test working directory. */
    private fun snippetsFile(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("editor/vscode/snippets/qkt.json")
            if (Files.isDirectory(candidate.parent)) return candidate
            dir = dir.parent
        }
        error("could not locate editor/vscode/snippets from ${Path.of("").toAbsolutePath()}")
    }

    /**
     * Resolve a snippet body to concrete text by taking the default of every tab stop: `${1:x}` to
     * `x`, `${1|a,b|}` to `a`, and a mirror `$1` to the same default. Lets the parse test run real
     * DSL through `DiagnosticsRunner`. e.g. `BUY ${1:alias}` with mirror `$1` resolves to `BUY alias`.
     */
    private fun render(body: List<String>): String {
        var text = body.joinToString("\n")
        val mirror = HashMap<Int, String>()
        Regex("""\$\{(\d+):([^{}]*)\}""").findAll(text).forEach {
            mirror.putIfAbsent(it.groupValues[1].toInt(), it.groupValues[2])
        }
        Regex("""\$\{(\d+)\|([^|{}]*)\|\}""").findAll(text).forEach {
            mirror.putIfAbsent(it.groupValues[1].toInt(), it.groupValues[2].substringBefore(","))
        }
        text = Regex("""\$\{(\d+)\|([^|{}]*)\|\}""").replace(text) { it.groupValues[2].substringBefore(",") }
        val named = Regex("""\$\{(\d+):([^{}]*)\}""")
        var prev: String
        do {
            prev = text
            text = named.replace(text) { it.groupValues[2] }
        } while (text != prev)
        return Regex("""\$(\d+)""").replace(text) { mirror[it.groupValues[1].toInt()] ?: "" }
    }
}
