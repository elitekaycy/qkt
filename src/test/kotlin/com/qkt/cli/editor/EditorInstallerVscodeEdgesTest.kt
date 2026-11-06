package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class EditorInstallerVscodeEdgesTest : EditorInstallerFixture() {
    @TempDir
    lateinit var tmp: Path

    private val out = StringBuilder()
    private val err = StringBuilder()

    private fun installer(
        root: Path? = fakeEditorRoot(tmp.resolve("share/editor")),
        codeBin: Path? = tmp.resolve("bin/code"),
        exitCode: Int = 0,
    ) = EditorInstaller(
        detector = detector(Files.createDirectories(tmp.resolve("home")), codeBin = codeBin),
        editorRoot = root,
        manifestPath = tmp.resolve("manifest.json"),
        out = out,
        err = err,
        processRunner = { _, _ -> exitCode },
        launcher = null,
    )

    @Test
    fun `uninstall needs the editor's command on the path`() {
        assertThat(installer(codeBin = null).uninstall(EditorTarget.VSCODE)).isFalse()
        assertThat(
            err.toString(),
        ).contains("'code'/'code-insiders' not on PATH — cannot uninstall the VSCode extension.")
    }

    @Test
    fun `a failed uninstall keeps the record and a successful one removes it`() {
        assertThat(installer(exitCode = 2).uninstall(EditorTarget.VSCODE)).isFalse()
        assertThat(err.toString()).contains("VSCode uninstall failed (exit 2)")

        assertThat(installer().uninstall(EditorTarget.VSCODE)).isTrue()
        assertThat(out.toString()).contains("qkt editor: removed VSCode extension")
    }

    @Test
    fun `a bundle without the vscode sources cannot install`() {
        val root = Files.createDirectories(tmp.resolve("bare"))

        assertThat(installer(root = root).install(EditorTarget.VSCODE)).isNull()
        assertThat(err.toString()).contains("qkt: editor source for VSCode not found at ${root.resolve("vscode")}")
    }

    @Test
    fun `an extension command that fails is an install failure`() {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        Files.writeString(root.resolve("vscode/qkt-0.1.0.vsix"), "binary placeholder")

        assertThat(installer(root = root, exitCode = 1).install(EditorTarget.VSCODE)).isNull()
        assertThat(err.toString()).contains("VSCode install failed (exit 1)")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `runProcess returns the command's exit code and minus one when it cannot start`() {
        assertThat(runProcess(listOf("sh", "-c", "exit 3"), tmp)).isEqualTo(3)
        assertThat(runProcess(listOf("sh", "-c", "test -d ."), null)).isZero()
        assertThat(runProcess(listOf(tmp.resolve("no-such-binary").toString()), null)).isEqualTo(-1)
    }
}
