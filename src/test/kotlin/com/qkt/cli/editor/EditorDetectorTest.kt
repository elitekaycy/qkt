package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorDetectorTest {
    @Test
    fun `vscode is detected when code is on PATH`(
        @TempDir tmp: Path,
    ) {
        val codeBin = tmp.resolve("code")
        Files.writeString(codeBin, "#!/bin/sh\nexit 0")
        codeBin.toFile().setExecutable(true)

        val det =
            EditorDetector(
                env = emptyMap(),
                home = tmp,
                pathLookup = { name -> if (name == "code") codeBin else null },
            )
        assertThat(det.detect(EditorTarget.VSCODE)).isTrue
        assertThat(det.vscodeCli()).isEqualTo(codeBin)
    }

    @Test
    fun `vscode is not detected when no code binary is on PATH`(
        @TempDir tmp: Path,
    ) {
        val det = EditorDetector(env = emptyMap(), home = tmp, pathLookup = { null })
        assertThat(det.detect(EditorTarget.VSCODE)).isFalse
        assertThat(det.vscodeCli()).isNull()
    }

    @Test
    fun `code-insiders satisfies vscode detection`(
        @TempDir tmp: Path,
    ) {
        val insiders = tmp.resolve("code-insiders")
        Files.writeString(insiders, "#!/bin/sh\nexit 0")
        insiders.toFile().setExecutable(true)

        val det =
            EditorDetector(
                env = emptyMap(),
                home = tmp,
                pathLookup = { name -> if (name == "code-insiders") insiders else null },
            )
        assertThat(det.detect(EditorTarget.VSCODE)).isTrue
    }

    @Test
    fun `nvim is detected when XDG_CONFIG_HOME-slash-nvim exists`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve("xdg/nvim"))
        val det =
            EditorDetector(
                env = mapOf("XDG_CONFIG_HOME" to tmp.resolve("xdg").toString()),
                home = tmp,
                pathLookup = { null },
            )
        assertThat(det.detect(EditorTarget.NVIM)).isTrue
        assertThat(det.nvimConfigDir()).isEqualTo(tmp.resolve("xdg/nvim"))
    }

    @Test
    fun `nvim falls back to home-dot-config when XDG is unset`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve(".config/nvim"))
        val det = EditorDetector(env = emptyMap(), home = tmp, pathLookup = { null })
        assertThat(det.detect(EditorTarget.NVIM)).isTrue
        assertThat(det.nvimConfigDir()).isEqualTo(tmp.resolve(".config/nvim"))
    }

    @Test
    fun `nvim is not detected when config dir is absent`(
        @TempDir tmp: Path,
    ) {
        val det = EditorDetector(env = emptyMap(), home = tmp, pathLookup = { null })
        assertThat(det.detect(EditorTarget.NVIM)).isFalse
    }

    @Test
    fun `vim detection probes home-dot-vim`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve(".vim"))
        val det = EditorDetector(env = emptyMap(), home = tmp, pathLookup = { null })
        assertThat(det.detect(EditorTarget.VIM)).isTrue
        assertThat(det.vimConfigDir()).isEqualTo(tmp.resolve(".vim"))
    }

    @Test
    fun `sublime on linux probes config-sublime-text-packages-user`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve(".config/sublime-text/Packages/User"))
        val det = EditorDetector(env = emptyMap(), home = tmp, osName = "linux", pathLookup = { null })
        assertThat(det.detect(EditorTarget.SUBLIME)).isTrue
    }

    @Test
    fun `sublime on macos probes Library-Application Support`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve("Library/Application Support/Sublime Text/Packages/User"))
        val det = EditorDetector(env = emptyMap(), home = tmp, osName = "mac os x", pathLookup = { null })
        assertThat(det.detect(EditorTarget.SUBLIME)).isTrue
    }

    @Test
    fun `all returns every target with its detection status`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve(".vim"))
        val det = EditorDetector(env = emptyMap(), home = tmp, pathLookup = { null })
        val map = det.all()
        assertThat(map.keys).containsExactlyInAnyOrder(*EditorTarget.entries.toTypedArray())
        assertThat(map[EditorTarget.VIM]).isTrue
        assertThat(map[EditorTarget.VSCODE]).isFalse
    }
}
