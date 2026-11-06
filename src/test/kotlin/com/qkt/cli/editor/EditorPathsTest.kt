package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorPathsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `QKT_HOME's share editor directory wins when it exists`() {
        val bundle = Files.createDirectories(tmp.resolve("home/share/editor"))
        Files.createDirectories(tmp.resolve("cwd/editor"))

        val root =
            EditorPaths.bundledEditorRoot(
                env = mapOf("QKT_HOME" to tmp.resolve("home").toString()),
                cwd = tmp.resolve("cwd"),
            )

        assertThat(root).isEqualTo(bundle)
    }

    @Test
    fun `a QKT_HOME without a bundle falls back to the checkout's editor directory`() {
        val checkout = Files.createDirectories(tmp.resolve("cwd/editor"))

        val root =
            EditorPaths.bundledEditorRoot(
                env = mapOf("QKT_HOME" to tmp.resolve("empty").toString()),
                cwd = tmp.resolve("cwd"),
            )

        assertThat(root).isEqualTo(checkout)
    }

    @Test
    fun `no bundle anywhere is null`() {
        assertThat(EditorPaths.bundledEditorRoot(env = emptyMap(), cwd = tmp)).isNull()
    }

    @Test
    fun `a test run started by java outside a packaged install has no recorded launcher`() {
        assertThat(EditorPaths.launcher()).isNull()
    }
}
