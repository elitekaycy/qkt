package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScriptDataFetcherTest {
    @TempDir lateinit var dir: Path

    private val windows = System.getProperty("os.name", "").lowercase().contains("win")

    /** A fetcher script for this OS: [sh] as bash, or [cmd] as a Windows batch file. */
    private fun writeScript(
        name: String,
        sh: String,
        cmd: String,
    ): Path {
        val path = dir.resolve(if (windows) "$name.cmd" else "$name.sh")
        Files.writeString(
            path,
            if (windows) "@echo off\r\n${cmd.replace("\n", "\r\n")}\r\n" else "#!/usr/bin/env bash\n$sh",
        )
        path.toFile().setExecutable(true)
        return path
    }

    @Test
    fun `successful script invocation produces target file`() {
        val d = "$"
        val script =
            writeScript(
                "ok",
                sh =
                    """
                    set -e
                    mkdir -p "$d(dirname "${d}3")"
                    echo "from-script:${d}1:${d}2" > "${d}3"
                    """.trimIndent(),
                cmd = "echo from-script:%1:%2> \"%~3\"",
            )
        val target = dir.resolve("out").resolve("file.csv.gz")
        ScriptDataFetcher(script).fetch("EURUSD", LocalDate.parse("2024-01-15"), target)
        assertThat(Files.exists(target)).isTrue()
        assertThat(Files.readString(target).trim()).isEqualTo("from-script:EURUSD:2024-01-15")
    }

    @Test
    fun `non zero exit throws with arguments`() {
        val script = writeScript("fail", sh = "exit 7", cmd = "exit /b 7")
        val target = dir.resolve("out").resolve("file.csv.gz")
        assertThatThrownBy {
            ScriptDataFetcher(script).fetch("X", LocalDate.parse("2024-01-15"), target)
        }.hasMessageContaining("rc=7")
            .hasMessageContaining("symbol=X")
    }

    @Test
    fun `script exit zero but missing target file throws`() {
        val script = writeScript("nofile", sh = "exit 0", cmd = "exit /b 0")
        val target = dir.resolve("out").resolve("file.csv.gz")
        assertThatThrownBy {
            ScriptDataFetcher(script).fetch("X", LocalDate.parse("2024-01-15"), target)
        }.hasMessageContaining("exited 0 but produced no file")
    }

    @Test
    fun `companion dukascopy wraps the supplied script path`() {
        val script = writeScript("dukascopy", sh = "exit 0", cmd = "exit /b 0")
        val fetcher = ScriptDataFetcher.dukascopy(script)
        assertThat(fetcher).isNotNull
    }

    @Test
    fun `nonexistent script path fails fast at construction`() {
        assertThatThrownBy { ScriptDataFetcher(dir.resolve("missing.sh")) }
            .hasMessageContaining("script not found")
    }

    @Test
    fun `the script extension picks how it runs`() {
        assertThat(ScriptDataFetcher.commandFor(Path.of("fetch.sh"))).containsExactly("bash", "fetch.sh")
        assertThat(ScriptDataFetcher.commandFor(Path.of("fetch.CMD"))).containsExactly("fetch.CMD")
        assertThat(ScriptDataFetcher.commandFor(Path.of("fetch.ps1")))
            .containsExactly("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "fetch.ps1")
    }
}
