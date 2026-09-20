package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorInstallerVimTest : EditorInstallerFixture() {
    @Test
    fun `nvim install copies three vim files into the config dir`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val installer =
            EditorInstaller(detector = detector(home), editorRoot = root, manifestPath = tmp.resolve("manifest.json"))

        val result = installer.install(EditorTarget.NVIM)

        assertThat(result).isNotNull
        assertThat(result!!.placedFiles).hasSize(3)
        val nvimConfig = home.resolve(".config/nvim")
        assertThat(nvimConfig.resolve("ftdetect/qkt.vim")).exists()
        assertThat(nvimConfig.resolve("ftplugin/qkt.vim")).exists()
        assertThat(nvimConfig.resolve("syntax/qkt.vim")).exists()
        assertThat(Files.readString(nvimConfig.resolve("syntax/qkt.vim")))
            .contains("syn keyword qktSection STRATEGY")
    }

    @Test
    fun `nvim install is idempotent — re-running overwrites in place`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val installer =
            EditorInstaller(detector = detector(home), editorRoot = root, manifestPath = tmp.resolve("manifest.json"))

        installer.install(EditorTarget.NVIM)
        // Mutate the source file (simulating an upgrade) and re-install.
        Files.writeString(root.resolve("nvim/syntax/qkt.vim"), "syn keyword qktSection FOO BAR\n")
        val result = installer.install(EditorTarget.NVIM)

        assertThat(result).isNotNull
        assertThat(Files.readString(home.resolve(".config/nvim/syntax/qkt.vim")))
            .contains("FOO BAR")
    }

    @Test
    fun `vim install targets home-dot-vim`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val installer =
            EditorInstaller(detector = detector(home), editorRoot = root, manifestPath = tmp.resolve("manifest.json"))

        val result = installer.install(EditorTarget.VIM)

        assertThat(result).isNotNull
        assertThat(home.resolve(".vim/ftdetect/qkt.vim")).exists()
        assertThat(home.resolve(".vim/ftplugin/qkt.vim")).exists()
        assertThat(home.resolve(".vim/syntax/qkt.vim")).exists()
    }

    @Test
    fun `sublime install writes the grammar as a sublime-syntax file`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val installer =
            EditorInstaller(detector = detector(home), editorRoot = root, manifestPath = tmp.resolve("manifest.json"))

        val result = installer.install(EditorTarget.SUBLIME)

        assertThat(result).isNotNull
        val dst = home.resolve(".config/sublime-text/Packages/User/qkt.sublime-syntax")
        assertThat(dst).exists()
        assertThat(Files.readString(dst)).contains("source.qkt")
    }
}
