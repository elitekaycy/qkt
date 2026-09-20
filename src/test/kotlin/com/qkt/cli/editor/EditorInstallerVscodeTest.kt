package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorInstallerVscodeTest : EditorInstallerFixture() {
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
