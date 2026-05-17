package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigLocateTest {
    @Test
    fun `locate returns first existing path from search list`(
        @TempDir tmp: Path,
    ) {
        val a = tmp.resolve("a.yaml")
        val b = tmp.resolve("b.yaml")
        val c = tmp.resolve("c.yaml")
        Files.writeString(b, "source: local")
        Files.writeString(c, "source: local")
        // b is first existing; a is missing; c exists too but b wins
        assertThat(Config.locate(listOf(a, b, c))).isEqualTo(b)
    }

    @Test
    fun `locate returns null when no path exists`(
        @TempDir tmp: Path,
    ) {
        val a = tmp.resolve("a.yaml")
        val b = tmp.resolve("b.yaml")
        assertThat(Config.locate(listOf(a, b))).isNull()
    }

    @Test
    fun `default search list includes pwd etc-qkt and home-qkt locations`() {
        val paths = Config.defaultSearchPaths()
        // Order is meaningful: pwd first (dev) then container-standard then user-home.
        assertThat(paths.map { it.toString() }).containsExactly(
            "./qkt.config.yaml",
            "/etc/qkt/qkt.config.yaml",
            System.getProperty("user.home") + "/.qkt/qkt.config.yaml",
        )
    }
}
