package com.qkt.docs

import com.qkt.cli.Args
import com.qkt.cli.ListCommand
import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import com.qkt.docs.DslSnippetCompiler.compile
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Output and code the docs show outside a ```qkt block (#1456): the `qkt list` table on the portfolio
 * tutorial is what [ListCommand] prints for the rows the daemon's `/list` returns, and the strategy on
 * the landing page, written as highlighted HTML, compiles as `qkt parse` would.
 */
class DocsShownOutputTest {
    @Test
    fun `the portfolio tutorial's qkt list table is what qkt list prints`(
        @TempDir tmp: Path,
    ) {
        val page = Files.readString(Path.of("docs", "tutorials", "compose-portfolio.md"))
        val shown =
            Regex("Expected output:\\s*```text\\n(NAME[^`]*?)```")
                .find(page)!!
                .groupValues[1]
                .trimEnd()
        // The rows ListRoute returns for that deploy, 42 s after it: a portfolio row carries no port or trades.
        val rows =
            """[{"name":"mybook","kind":"portfolio","childAliases":["trend","meanrev"],"uptimeMs":42000,"state":"running"},
               {"name":"mybook/trend","kind":"child","parent":"mybook","port":47292,"trades":0,"uptimeMs":42000,
                "state":"running","gateState":"idle","streamBrokers":{}},
               {"name":"mybook/meanrev","kind":"child","parent":"mybook","port":47293,"trades":0,"uptimeMs":42000,
                "state":"running","gateState":"idle","streamBrokers":{}}]"""
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun list(): String = rows
            }
        val out = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(out))
        try {
            ListCommand(Args(arrayOf("list", "--state-dir", tmp.toString()))) { client }.run()
        } finally {
            System.setOut(saved)
        }

        assertThat(out.toString().replace("\r\n", "\n").trimEnd()).isEqualTo(shown)
    }

    @Test
    fun `the landing page strategy compiles`(
        @TempDir tmp: Path,
    ) {
        val page = Files.readString(Path.of("docs", "index.md"))
        val html =
            Regex("<pre class=\"qkt-codeframe__body\"><code>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)
                .find(page)!!
                .groupValues[1]
        val source =
            html
                .replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")

        assertThat(source).startsWith("STRATEGY momentum VERSION 1")
        assertThat(
            compile(source, tmp),
        ).withFailMessage("docs/index.md strategy: %s%n%s", compile(source, tmp), source).isNull()
    }
}
