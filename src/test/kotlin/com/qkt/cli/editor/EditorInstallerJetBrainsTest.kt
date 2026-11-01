package com.qkt.cli.editor

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorInstallerJetBrainsTest : EditorInstallerFixture() {
    private fun zip(
        file: Path,
        entry: String,
    ): Path {
        Files.createDirectories(file.parent)
        ZipOutputStream(Files.newOutputStream(file)).use {
            it.putNextEntry(ZipEntry(entry))
            it.write("x".toByteArray())
            it.closeEntry()
        }
        return file
    }

    private fun windowsDetector(
        home: Path,
        vararg ides: String,
    ): EditorDetector {
        val appData = home.resolve("AppData")
        ides.forEach { Files.createDirectories(appData.resolve("JetBrains").resolve(it)) }
        return EditorDetector(env = mapOf("APPDATA" to appData.toString()), home = home, pathLookup = { null })
    }

    private fun installer(
        home: Path,
        detector: EditorDetector,
        launcher: Path? = null,
        err: StringBuilder = StringBuilder(),
        download: (String, Path) -> Unit,
    ): EditorInstaller {
        val root = fakeEditorRoot(home.resolve("editor"))
        zip(root.resolve("jetbrains/qkt-jetbrains-0.1.0.zip"), "qkt-jetbrains/lib/qkt.jar")
        return EditorInstaller(
            detector = detector,
            editorRoot = root,
            manifestPath = home.resolve("cfg/manifest.json"),
            out = StringBuilder(),
            err = err,
            processRunner = { _, _ -> 0 },
            pathLookup = { null },
            launcher = launcher,
            download = download,
        )
    }

    @Test
    fun `places the plugin and lsp4ij in each supported ide`(
        @TempDir home: Path,
    ) {
        val urls = mutableListOf<String>()
        val detector = windowsDetector(home, "IntelliJIdea2025.3", "PyCharm2024.1", "consentOptions")
        val result =
            installer(home, detector) { url, file ->
                urls.add(url)
                zip(file, "lsp4ij/lib/lsp4ij.jar")
            }.install(EditorTarget.INTELLIJ)

        val plugins = home.resolve("AppData/JetBrains/IntelliJIdea2025.3/plugins")
        assertThat(plugins.resolve("qkt-jetbrains/lib/qkt.jar")).exists()
        assertThat(plugins.resolve("lsp4ij/lib/lsp4ij.jar")).exists()
        assertThat(urls).hasSize(1)
        assertThat(urls.single()).endsWith("build=IU-253.1")
        assertThat(home.resolve("AppData/JetBrains/PyCharm2024.1/plugins")).doesNotExist()
        assertThat(result?.placedFiles).containsExactly(plugins.resolve("qkt-jetbrains"))
    }

    @Test
    fun `keeps an installed lsp4ij and replaces an older qkt plugin`(
        @TempDir home: Path,
    ) {
        val detector = windowsDetector(home, "IntelliJIdea2025.3")
        val plugins = home.resolve("AppData/JetBrains/IntelliJIdea2025.3/plugins")
        Files.createDirectories(plugins.resolve("lsp4ij"))
        Files.createDirectories(plugins.resolve("qkt-jetbrains"))
        Files.writeString(plugins.resolve("qkt-jetbrains/old.jar"), "old")

        installer(home, detector) { _, _ -> error("must not download") }.install(EditorTarget.INTELLIJ)

        assertThat(plugins.resolve("qkt-jetbrains/old.jar")).doesNotExist()
        assertThat(plugins.resolve("qkt-jetbrains/lib/qkt.jar")).exists()
    }

    @Test
    fun `a failed lsp4ij download still installs the plugin and says what to do`(
        @TempDir home: Path,
    ) {
        val err = StringBuilder()
        val detector = windowsDetector(home, "IntelliJIdea2025.3")
        val result =
            installer(home, detector, err = err) { _, _ -> throw IOException("offline") }.install(EditorTarget.INTELLIJ)

        assertThat(result).isNotNull
        assertThat(err.toString()).contains("offline", "Settings > Plugins")
    }

    @Test
    fun `uninstall removes the plugin and leaves lsp4ij`(
        @TempDir home: Path,
    ) {
        val detector = windowsDetector(home, "IntelliJIdea2025.3")
        val installer = installer(home, detector) { _, file -> zip(file, "lsp4ij/lib/lsp4ij.jar") }
        installer.install(EditorTarget.INTELLIJ)

        assertThat(installer.uninstall(EditorTarget.INTELLIJ)).isTrue()

        val plugins = home.resolve("AppData/JetBrains/IntelliJIdea2025.3/plugins")
        assertThat(plugins.resolve("qkt-jetbrains")).doesNotExist()
        assertThat(plugins.resolve("lsp4ij")).exists()
    }

    @Test
    fun `an archive entry that escapes the plugins folder is refused`(
        @TempDir home: Path,
    ) {
        val err = StringBuilder()
        val detector = windowsDetector(home, "IntelliJIdea2025.3")
        val installer = installer(home, detector, err = err) { _, _ -> }
        zip(home.resolve("editor/jetbrains/qkt-jetbrains-0.1.0.zip"), "../../evil.jar")

        assertThat(installer.install(EditorTarget.INTELLIJ)).isNull()
        assertThat(home.resolve("AppData/JetBrains/evil.jar")).doesNotExist()
    }

    @Test
    fun `installing records the launcher for editor plugins`(
        @TempDir home: Path,
    ) {
        val qkt = home.resolve("bin/qkt")
        val detector = windowsDetector(home, "IntelliJIdea2025.3")
        installer(home, detector, launcher = qkt) { _, file -> zip(file, "lsp4ij/x") }.install(EditorTarget.INTELLIJ)

        assertThat(Files.readString(home.resolve("cfg/qkt-path"))).isEqualTo(qkt.toString())
    }
}
