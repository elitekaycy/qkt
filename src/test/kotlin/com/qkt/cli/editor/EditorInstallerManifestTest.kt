package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorInstallerManifestTest : EditorInstallerFixture() {
    @Test
    fun `successful install writes manifest entry`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val manifestPath = tmp.resolve("manifest.json")
        val installer = EditorInstaller(detector = detector(home), editorRoot = root, manifestPath = manifestPath)

        installer.install(EditorTarget.NVIM)

        val manifest = EditorManifest.load(manifestPath)
        val entry = manifest.recordFor(EditorTarget.NVIM)
        assertThat(entry).isNotNull
        assertThat(entry!!.files).hasSize(3)
        assertThat(entry.files).allSatisfy { p ->
            assertThat(p).contains(".config/nvim")
        }
    }

    @Test
    fun `uninstall removes placed files and the manifest entry`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val manifestPath = tmp.resolve("manifest.json")
        val installer = EditorInstaller(detector = detector(home), editorRoot = root, manifestPath = manifestPath)
        installer.install(EditorTarget.NVIM)

        val ok = installer.uninstall(EditorTarget.NVIM)

        assertThat(ok).isTrue
        assertThat(home.resolve(".config/nvim/syntax/qkt.vim")).doesNotExist()
        assertThat(home.resolve(".config/nvim/ftdetect/qkt.vim")).doesNotExist()
        assertThat(EditorManifest.load(manifestPath).recordFor(EditorTarget.NVIM)).isNull()
    }

    @Test
    fun `uninstall refuses when no manifest record exists`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val errBuf = StringBuilder()
        val installer =
            EditorInstaller(
                detector = detector(home),
                editorRoot = root,
                manifestPath = tmp.resolve("manifest.json"),
                err = errBuf,
            )

        val ok = installer.uninstall(EditorTarget.NVIM)

        assertThat(ok).isFalse
        assertThat(errBuf.toString()).contains("no install record")
    }

    @Test
    fun `uninstall vscode shells out to code --uninstall-extension`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val codeBin = tmp.resolve("bin/code")
        Files.createDirectories(codeBin.parent)
        Files.writeString(codeBin, "#!/bin/sh\nexit 0")
        codeBin.toFile().setExecutable(true)

        val recordedCommands = mutableListOf<List<String>>()
        val installer =
            EditorInstaller(
                detector = detector(home, codeBin = codeBin),
                editorRoot = root,
                manifestPath = tmp.resolve("manifest.json"),
                processRunner = { cmd, _ ->
                    recordedCommands.add(cmd)
                    0
                },
            )

        val ok = installer.uninstall(EditorTarget.VSCODE)

        assertThat(ok).isTrue
        assertThat(recordedCommands).hasSize(1)
        assertThat(recordedCommands.single())
            .containsExactly(codeBin.toString(), "--uninstall-extension", "elitekaycy.qkt")
    }
}
