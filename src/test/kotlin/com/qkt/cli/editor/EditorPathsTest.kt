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

    @Test
    fun `the install root is the directory above lib for a jar in a packaged install`() {
        val jar = Files.createDirectories(tmp.resolve("app/lib")).resolve("qkt.jar")

        assertThat(EditorPaths.appHomeOf(jar.toUri().toURL())).isEqualTo(tmp.resolve("app"))
    }

    @Test
    fun `a jar outside a lib directory or at a non-file location has no install root`() {
        assertThat(EditorPaths.appHomeOf(tmp.resolve("build/classes/qkt.jar").toUri().toURL())).isNull()
        assertThat(EditorPaths.appHomeOf(java.net.URL("http://example.com/lib/qkt.jar"))).isNull()
    }

    @Test
    fun `a packaged install's share editor directory is used when QKT_HOME has none`() {
        val bundle = Files.createDirectories(tmp.resolve("app/share/editor"))

        val root =
            EditorPaths.bundledEditorRoot(
                env = emptyMap(),
                cwd = tmp.resolve("cwd"),
                appHome = tmp.resolve("app"),
            )

        assertThat(root).isEqualTo(bundle)
        assertThat(
            EditorPaths.bundledEditorRoot(env = emptyMap(), cwd = tmp.resolve("cwd"), appHome = tmp.resolve("none")),
        ).isNull()
    }

    @Test
    fun `the launcher is the running qkt command, else the packaged script when it exists`() {
        val bin = Files.createDirectories(tmp.resolve("app/bin"))
        val script = Files.writeString(bin.resolve("qkt"), "#!/bin/sh\n")
        val batch = Files.writeString(bin.resolve("qkt.bat"), "@echo off\n")
        val java = Path.of("/usr/bin/java")

        assertThat(EditorPaths.launcher(Path.of("/opt/qkt.exe"), tmp.resolve("app"), windows = false))
            .isEqualTo(Path.of("/opt/qkt.exe"))
        assertThat(EditorPaths.launcher(java, tmp.resolve("app"), windows = false)).isEqualTo(script)
        assertThat(EditorPaths.launcher(java, tmp.resolve("app"), windows = true)).isEqualTo(batch)
        assertThat(EditorPaths.launcher(java, tmp.resolve("empty"), windows = false)).isNull()
        assertThat(EditorPaths.launcher(java, null, windows = false)).isNull()
    }
}
