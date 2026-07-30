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
    fun `resolvePath honors explicit then documented locations then dev fallback`(
        @TempDir tmp: Path,
    ) {
        val missing = tmp.resolve("missing.yaml")
        val located = tmp.resolve("located.yaml")
        val explicit = tmp.resolve("explicit.yaml")
        Files.writeString(located, "runtime:\n  mode: production")

        assertThat(Config.resolvePath(explicit.toString(), listOf(missing, located), emptyMap())).isEqualTo(explicit)
        assertThat(Config.resolvePath(null, listOf(missing, located), emptyMap())).isEqualTo(located)
        assertThat(Config.resolvePath(null, listOf(missing), emptyMap())).isEqualTo(Path.of("./qkt.config.yaml"))
    }

    @Test
    fun `resolvePath honors QKT_CONFIG after an explicit flag and before default locations`(
        @TempDir tmp: Path,
    ) {
        val explicit = tmp.resolve("explicit.yaml")
        val fromEnv = tmp.resolve("env.yaml")
        val located = tmp.resolve("located.yaml")

        assertThat(Config.resolvePath(null, listOf(located), mapOf("QKT_CONFIG" to fromEnv.toString())))
            .isEqualTo(fromEnv)
        assertThat(
            Config.resolvePath(
                explicit.toString(),
                listOf(located),
                mapOf("QKT_CONFIG" to fromEnv.toString()),
            ),
        ).isEqualTo(explicit)
    }

    @Test
    fun `non-windows search list is pwd, etc-qkt, config-home, home-qkt`() {
        val home = Path.of("/home/me")
        val ud = UserDirs(osName = "linux", env = emptyMap(), home = home)
        val paths = Config.defaultSearchPaths(userDirs = ud, home = home)
        assertThat(paths.map { it.toString() }).containsExactly(
            "./qkt.config.yaml",
            "/etc/qkt/qkt.config.yaml",
            "/home/me/.config/qkt/qkt.config.yaml",
            "/home/me/.qkt/qkt.config.yaml",
        )
    }

    @Test
    fun `windows search list uses APPDATA and omits etc-qkt`() {
        val home = Path.of("/fake/home")
        val ud = UserDirs(osName = "windows 11", env = mapOf("APPDATA" to "/fake/Roaming"), home = home)
        val paths = Config.defaultSearchPaths(userDirs = ud, home = home).map { it.toString() }
        assertThat(paths).containsExactly(
            "./qkt.config.yaml",
            "/fake/Roaming/qkt/qkt.config.yaml",
            "/fake/home/.qkt/qkt.config.yaml",
        )
        assertThat(paths).noneMatch { it.contains("/etc/qkt") }
    }
}
