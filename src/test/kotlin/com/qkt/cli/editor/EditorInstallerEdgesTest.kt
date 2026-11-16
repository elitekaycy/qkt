package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Installs that cannot proceed, and the editors qkt places nothing for. */
class EditorInstallerEdgesTest : EditorInstallerFixture() {
    @TempDir
    lateinit var tmp: Path

    private val out = StringBuilder()
    private val err = StringBuilder()
    private val home get() = Files.createDirectories(tmp.resolve("home"))
    private val manifestPath get() = tmp.resolve("cfg/manifest.json")

    private fun installer(
        root: Path?,
        detector: EditorDetector = detector(home),
        download: (String, Path) -> Unit = { _, _ -> error("no download expected") },
    ) = EditorInstaller(
        detector = detector,
        editorRoot = root,
        manifestPath = manifestPath,
        out = out,
        err = err,
        processRunner = { _, _ -> 0 },
        pathLookup = { null },
        launcher = null,
        download = download,
    )

    private fun pluginZip(
        root: Path,
        vararg entries: String,
    ) {
        val file = root.resolve("jetbrains/qkt-jetbrains-0.1.0.zip")
        Files.createDirectories(file.parent)
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry))
                if (!entry.endsWith("/")) zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `without a bundle nothing is installed and the error says how to point at one`() {
        assertThat(installer(root = null).install(EditorTarget.VIM)).isNull()

        assertThat(err.toString())
            .isEqualTo("qkt: cannot locate bundled editor files (set QKT_HOME or run from a packaged install)\n")
        assertThat(manifestPath).doesNotExist()
        assertThat(home.resolve(".vim")).doesNotExist()
    }

    @Test
    fun `zed install prints the in-editor steps with the dev extension path and records nothing`() {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))

        val result = installer(root).install(EditorTarget.ZED)

        assertThat(result).isEqualTo(EditorInstaller.InstallResult(EditorTarget.ZED, emptyList()))
        assertThat(out.lines()).containsExactly(
            "qkt editor: Zed installs extensions from inside the editor:",
            "     1. Open Zed's command palette and run: zed: extensions",
            "     2. Search for qkt and click Install.",
            "     Not listed yet? Run zed: install dev extension and choose ${root.resolve("zed").toAbsolutePath()}",
            "     Completion and diagnostics need qkt on the PATH Zed sees.",
            "",
        )
        assertThat(manifestPath).doesNotExist()
    }

    @Test
    fun `zed uninstall succeeds by pointing at zed's own settings`() {
        assertThat(installer(root = null).uninstall(EditorTarget.ZED)).isTrue()

        assertThat(out.toString())
            .isEqualTo("qkt editor: remove qkt from Zed in its own settings; qkt placed no files there\n")
        assertThat(err).isEmpty()
    }

    @Test
    fun `a bundle without the vim sources cannot install vim`() {
        val root = Files.createDirectories(tmp.resolve("empty-bundle"))

        assertThat(installer(root).install(EditorTarget.VIM)).isNull()

        assertThat(err.toString()).isEqualTo("qkt: editor source for Vim not found at ${root.resolve("nvim")}\n")
        assertThat(manifestPath).doesNotExist()
    }

    @Test
    fun `vim install places only the files the bundle has`() {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        Files.delete(root.resolve("nvim/ftplugin/qkt.vim"))

        val result = installer(root).install(EditorTarget.VIM)

        val vim = home.resolve(".vim")
        assertThat(result?.placedFiles).containsExactly(vim.resolve("ftdetect/qkt.vim"), vim.resolve("syntax/qkt.vim"))
        assertThat(vim.resolve("ftplugin")).doesNotExist()
        assertThat(out.toString()).isEqualTo("qkt editor: installed Vim plugin (2 files at $vim)\n")
        assertThat(EditorManifest.load(manifestPath).recordFor(EditorTarget.VIM)?.files).hasSize(2)
    }

    @Test
    fun `helix and sublime refuse a bundle that lacks their files`() {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        Files.createDirectories(root.resolve("sublime"))
        Files.writeString(root.resolve("sublime/qkt.tmLanguage"), "<plist/>")

        assertThat(installer(root).install(EditorTarget.HELIX)).isNull()
        assertThat(installer(root).install(EditorTarget.SUBLIME)).isNull()

        assertThat(err.lines()).containsExactly(
            "qkt: tree-sitter grammar for Helix not found at ${root.resolve("tree-sitter-qkt").toAbsolutePath()}",
            "qkt: editor files not found at ${root.resolve("sublime")}",
            "",
        )
        assertThat(home.resolve(".config")).doesNotExist()
        assertThat(manifestPath).doesNotExist()
    }

    @Test
    fun `a bundled JetBrains plugin needs an IDE from 2024-2 or newer`() {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        pluginZip(root, "qkt-jetbrains/lib/qkt.jar")
        Files.createDirectories(home.resolve(".config/JetBrains/IntelliJIdea2024.1"))

        assertThat(installer(root).install(EditorTarget.INTELLIJ)).isNull()

        assertThat(err.toString())
            .isEqualTo("qkt: no JetBrains IDE from 2024.2 or newer found (open the IDE once, then re-run)\n")
        assertThat(home.resolve(".local")).doesNotExist()
    }

    @Test
    fun `a plugin archive with directory entries is unpacked and lsp4ij is fetched for the ide's build`() {
        val root = fakeEditorRoot(tmp.resolve("share/editor"))
        pluginZip(root, "qkt-jetbrains/", "qkt-jetbrains/lib/", "qkt-jetbrains/lib/qkt.jar")
        Files.createDirectories(home.resolve(".config/JetBrains/GoLand2025.1"))
        val lsp4ij = tmp.resolve("lsp4ij.zip")
        ZipOutputStream(Files.newOutputStream(lsp4ij)).use {
            it.putNextEntry(ZipEntry("lsp4ij/lib/lsp4ij.jar"))
            it.write("y".toByteArray())
            it.closeEntry()
        }
        val asked = mutableListOf<String>()

        val result =
            installer(root, download = { url, file ->
                asked.add(url)
                downloadTo(lsp4ij.toUri().toString(), file)
            }).install(EditorTarget.INTELLIJ)

        val plugins = home.resolve(".local/share/JetBrains/GoLand2025.1")
        assertThat(result?.placedFiles).containsExactly(plugins.resolve("qkt-jetbrains"))
        assertThat(plugins.resolve("qkt-jetbrains/lib/qkt.jar")).hasContent("x")
        assertThat(plugins.resolve("lsp4ij/lib/lsp4ij.jar")).hasContent("y")
        assertThat(asked.single()).contains("id=com.redhat.devtools.lsp4ij").contains("&build=GO-")
        assertThat(out.toString()).contains("installed the qkt plugin for GoLand2025.1 and LSP4IJ")
    }
}
