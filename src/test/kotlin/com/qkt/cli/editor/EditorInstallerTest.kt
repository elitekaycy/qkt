package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorInstallerTest {
    /** Mirrors the bundled share/editor/ layout under [root]. */
    private fun fakeEditorRoot(root: Path): Path {
        val nvim = root.resolve("nvim")
        Files.createDirectories(nvim.resolve("ftdetect"))
        Files.createDirectories(nvim.resolve("ftplugin"))
        Files.createDirectories(nvim.resolve("syntax"))
        Files.writeString(nvim.resolve("ftdetect/qkt.vim"), "au BufRead,BufNewFile *.qkt setfiletype qkt\n")
        Files.writeString(nvim.resolve("ftplugin/qkt.vim"), "setlocal commentstring=--\\ %s\n")
        Files.writeString(nvim.resolve("syntax/qkt.vim"), "syn keyword qktSection STRATEGY\n")

        val textmate = root.resolve("textmate")
        Files.createDirectories(textmate)
        Files.writeString(textmate.resolve("qkt.tmLanguage.json"), """{"scopeName":"source.qkt"}""")

        val vscode = root.resolve("vscode")
        Files.createDirectories(vscode)
        Files.writeString(vscode.resolve("package.json"), """{"name":"qkt","version":"0.1.0"}""")

        return root
    }

    private fun detector(
        home: Path,
        codeBin: Path? = null,
    ): EditorDetector =
        EditorDetector(
            env = emptyMap(),
            home = home,
            osName = "linux",
            pathLookup = { name -> if (name == "code" && codeBin != null) codeBin else null },
        )

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

    @Test
    fun `vscode install fails clearly when code is not on PATH`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val errBuf = StringBuilder()
        val installer =
            EditorInstaller(
                detector = detector(home, codeBin = null),
                editorRoot = root,
                manifestPath = tmp.resolve("manifest.json"),
                out = StringBuilder(),
                err = errBuf,
            )

        val result = installer.install(EditorTarget.VSCODE)

        assertThat(result).isNull()
        assertThat(errBuf.toString()).contains("not on PATH")
    }

    @Test
    fun `vscode install invokes code --install-extension when a bundled vsix exists`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        Files.writeString(root.resolve("vscode/qkt-0.1.0.vsix"), "binary placeholder")
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

        val result = installer.install(EditorTarget.VSCODE)

        assertThat(result).isNotNull
        assertThat(recordedCommands).hasSize(1)
        assertThat(recordedCommands.single())
            .containsExactly(
                codeBin.toString(),
                "--install-extension",
                root.resolve("vscode/qkt-0.1.0.vsix").toString(),
            )
    }

    @Test
    fun `vscode install builds the vsix from source via npx when no bundled vsix exists`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val codeBin = tmp.resolve("bin/code")
        Files.createDirectories(codeBin.parent)
        Files.writeString(codeBin, "#!/bin/sh\nexit 0")
        codeBin.toFile().setExecutable(true)
        val npxBin = tmp.resolve("bin/npx")
        Files.writeString(npxBin, "#!/bin/sh\nexit 0")
        npxBin.toFile().setExecutable(true)

        val recordedCommands = mutableListOf<List<String>>()
        val installer =
            EditorInstaller(
                detector = detector(home, codeBin = codeBin),
                editorRoot = root,
                manifestPath = tmp.resolve("manifest.json"),
                processRunner = { cmd, _ ->
                    recordedCommands.add(cmd)
                    if (cmd.first().endsWith("npx")) {
                        // Simulate vsce producing the .vsix.
                        Files.writeString(root.resolve("vscode/qkt-built.vsix"), "binary")
                    }
                    0
                },
                pathLookup = { name -> if (name == "npx") npxBin else null },
            )

        val result = installer.install(EditorTarget.VSCODE)

        assertThat(result).isNotNull
        assertThat(recordedCommands).hasSize(2)
        assertThat(recordedCommands[0]).contains("@vscode/vsce@latest", "package")
        assertThat(recordedCommands[1]).contains("--install-extension")
        assertThat(recordedCommands[1].last()).endsWith("qkt-built.vsix")
    }

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

    @Test
    fun `vscode install fails when no vsix and no npx`(
        @TempDir tmp: Path,
    ) {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        val home = tmp.resolve("home")
        Files.createDirectories(home)
        val codeBin = tmp.resolve("bin/code")
        Files.createDirectories(codeBin.parent)
        Files.writeString(codeBin, "#!/bin/sh\nexit 0")
        codeBin.toFile().setExecutable(true)

        val errBuf = StringBuilder()
        val installer =
            EditorInstaller(
                detector = detector(home, codeBin = codeBin),
                editorRoot = root,
                manifestPath = tmp.resolve("manifest.json"),
                err = errBuf,
                processRunner = { _, _ -> 1 },
                pathLookup = { null },
            )

        val result = installer.install(EditorTarget.VSCODE)

        assertThat(result).isNull()
        assertThat(errBuf.toString()).contains("no .vsix bundled")
    }
}
