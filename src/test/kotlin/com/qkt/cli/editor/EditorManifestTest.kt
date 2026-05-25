package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorManifestTest {
    @Test
    fun `load on missing file returns empty manifest`(
        @TempDir tmp: Path,
    ) {
        val m = EditorManifest.load(tmp.resolve("does-not-exist.json"))
        assertThat(m.installs).isEmpty()
    }

    @Test
    fun `save then load round-trips`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("manifest.json")
        val m =
            EditorManifest().withInstall(
                EditorTarget.NVIM,
                listOf(Path.of("/x/y"), Path.of("/x/z")),
                now = 12345L,
            )
        EditorManifest.save(path, m)
        val reloaded = EditorManifest.load(path)
        assertThat(reloaded.installs).hasSize(1)
        val entry = reloaded.installs.single()
        assertThat(entry.target).isEqualTo(EditorTarget.NVIM)
        assertThat(entry.files).containsExactly("/x/y", "/x/z")
        assertThat(entry.installedAt).isEqualTo(12345L)
    }

    @Test
    fun `withInstall on the same target replaces the prior entry`() {
        val first =
            EditorManifest().withInstall(EditorTarget.NVIM, listOf(Path.of("/old")), now = 1L)
        val second = first.withInstall(EditorTarget.NVIM, listOf(Path.of("/new")), now = 2L)
        assertThat(second.installs).hasSize(1)
        assertThat(second.installs.single().files).containsExactly("/new")
        assertThat(second.installs.single().installedAt).isEqualTo(2L)
    }

    @Test
    fun `withInstall preserves other targets`() {
        val m =
            EditorManifest()
                .withInstall(EditorTarget.NVIM, listOf(Path.of("/n")), now = 1L)
                .withInstall(EditorTarget.VIM, listOf(Path.of("/v")), now = 2L)
        assertThat(m.installs).hasSize(2)
        assertThat(m.recordFor(EditorTarget.NVIM)?.files).containsExactly("/n")
        assertThat(m.recordFor(EditorTarget.VIM)?.files).containsExactly("/v")
    }

    @Test
    fun `withoutInstall removes the matching entry only`() {
        val m =
            EditorManifest()
                .withInstall(EditorTarget.NVIM, listOf(Path.of("/n")), now = 1L)
                .withInstall(EditorTarget.VIM, listOf(Path.of("/v")), now = 2L)
                .withoutInstall(EditorTarget.NVIM)
        assertThat(m.installs).hasSize(1)
        assertThat(m.recordFor(EditorTarget.VIM)).isNotNull
        assertThat(m.recordFor(EditorTarget.NVIM)).isNull()
    }

    @Test
    fun `defaultPath respects XDG_CONFIG_HOME`(
        @TempDir tmp: Path,
    ) {
        val xdg = tmp.resolve("xdg-config")
        val p = EditorManifest.defaultPath(env = mapOf("XDG_CONFIG_HOME" to xdg.toString()), home = tmp)
        assertThat(p).isEqualTo(xdg.resolve("qkt/editor-install.json"))
    }

    @Test
    fun `defaultPath falls back to home-dot-config when XDG is unset`(
        @TempDir tmp: Path,
    ) {
        val p = EditorManifest.defaultPath(env = emptyMap(), home = tmp)
        assertThat(p).isEqualTo(tmp.resolve(".config/qkt/editor-install.json"))
    }

    @Test
    fun `save creates parent directories`(
        @TempDir tmp: Path,
    ) {
        val nested = tmp.resolve("a/b/c/manifest.json")
        EditorManifest.save(nested, EditorManifest())
        assertThat(Files.exists(nested)).isTrue
    }
}
