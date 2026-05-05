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

    private fun writeScript(
        name: String,
        body: String,
    ): Path {
        val path = dir.resolve(name)
        Files.writeString(path, "#!/usr/bin/env bash\n$body")
        path.toFile().setExecutable(true)
        return path
    }

    @Test
    fun `successful script invocation produces target file`() {
        val d = "$"
        val script =
            writeScript(
                "ok.sh",
                """
                set -e
                mkdir -p "$d(dirname "${d}3")"
                echo "from-script:${d}1:${d}2" > "${d}3"
                """.trimIndent(),
            )
        val target = dir.resolve("out").resolve("file.csv.gz")
        ScriptDataFetcher(script).fetch("EURUSD", LocalDate.parse("2024-01-15"), target)
        assertThat(Files.exists(target)).isTrue()
        assertThat(Files.readString(target).trim()).isEqualTo("from-script:EURUSD:2024-01-15")
    }

    @Test
    fun `non zero exit throws with arguments`() {
        val script = writeScript("fail.sh", "exit 7")
        val target = dir.resolve("out").resolve("file.csv.gz")
        assertThatThrownBy {
            ScriptDataFetcher(script).fetch("X", LocalDate.parse("2024-01-15"), target)
        }.hasMessageContaining("rc=7")
            .hasMessageContaining("symbol=X")
    }

    @Test
    fun `script exit zero but missing target file throws`() {
        val script = writeScript("nofile.sh", "exit 0")
        val target = dir.resolve("out").resolve("file.csv.gz")
        assertThatThrownBy {
            ScriptDataFetcher(script).fetch("X", LocalDate.parse("2024-01-15"), target)
        }.hasMessageContaining("exited 0 but produced no file")
    }

    @Test
    fun `companion dukascopy points at scripts dir`() {
        val fetcher = ScriptDataFetcher.dukascopy()
        assertThat(fetcher).isNotNull
    }
}
