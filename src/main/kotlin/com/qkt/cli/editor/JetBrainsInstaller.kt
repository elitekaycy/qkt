package com.qkt.cli.editor

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Installs the qkt plugin into every JetBrains IDE found on this machine, by unpacking it into the
 * IDE's plugins folder. That needs neither the IDE's command line nor its settings UI, and the IDE
 * loads it on the next start. The plugin needs LSP4IJ, so that comes from the JetBrains Marketplace
 * unless the IDE already has it.
 *
 * A source build has no plugin zip (building one downloads an IDE), so it prints the manual steps instead.
 */
internal class JetBrainsInstaller(
    private val detector: EditorDetector,
    private val out: Appendable,
    private val err: Appendable,
    private val download: (String, Path) -> Unit = ::downloadTo,
) {
    fun install(root: Path): EditorInstaller.InstallResult? {
        val zip = bundledPlugin(root) ?: return printManualSteps(root)
        val ides = detector.jetBrainsIdes().filter { it.supported }
        if (ides.isEmpty()) {
            err.appendLine("qkt: no JetBrains IDE from 2024.2 or newer found (open the IDE once, then re-run)")
            return null
        }
        val placed = mutableListOf<Path>()
        for (ide in ides) {
            try {
                Files.createDirectories(ide.pluginsDir)
                val plugin = ide.pluginsDir.resolve(PLUGIN_DIR)
                deleteRecursively(plugin)
                unzip(zip, ide.pluginsDir)
                placed.add(plugin)
                val lsp4ij = installLsp4ij(ide)
                out.appendLine("qkt editor: installed the qkt plugin for ${ide.name}$lsp4ij")
            } catch (e: IOException) {
                err.appendLine("qkt: could not install into ${ide.pluginsDir}: ${e.message}")
                err.appendLine("     If ${ide.name} is running, close it and re-run this command.")
                return null
            }
        }
        out.appendLine("qkt editor: restart the IDE to load it")
        return EditorInstaller.InstallResult(EditorTarget.INTELLIJ, placed)
    }

    /** Removes the qkt plugin from every IDE. LSP4IJ stays, since other plugins may rely on it. */
    fun uninstall(): Boolean {
        for (ide in detector.jetBrainsIdes()) {
            val plugin = ide.pluginsDir.resolve(PLUGIN_DIR)
            if (!Files.exists(plugin)) continue
            deleteRecursively(plugin)
            out.appendLine("qkt editor: removed the qkt plugin from ${ide.name}")
        }
        return true
    }

    private fun installLsp4ij(ide: JetBrainsIde): String {
        if (Files.isDirectory(ide.pluginsDir.resolve("lsp4ij"))) return ""
        val tmp = Files.createTempFile("lsp4ij", ".zip")
        return try {
            download("$MARKETPLACE_DOWNLOAD&build=${ide.buildCode}", tmp)
            unzip(tmp, ide.pluginsDir)
            " and LSP4IJ"
        } catch (e: IOException) {
            err.appendLine("qkt: could not download LSP4IJ for ${ide.name}: ${e.message}")
            err.appendLine("     Highlighting needs it too: install LSP4IJ from the IDE's Settings > Plugins.")
            ""
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun bundledPlugin(root: Path): Path? {
        val dir = root.resolve("jetbrains")
        if (!Files.isDirectory(dir)) return null
        return Files.list(dir).use { files ->
            files
                .filter { it.fileName.toString().matches(Regex("qkt-jetbrains-.*\\.zip")) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun printManualSteps(root: Path): EditorInstaller.InstallResult {
        val bundle = root.resolve("vscode").toAbsolutePath().normalize()
        out.appendLine("qkt editor: this build has no JetBrains plugin (release builds do), so set it up by hand:")
        out.appendLine("     1. Highlighting: Settings > Editor > TextMate Bundles > + and choose $bundle")
        out.appendLine("     2. Completion and diagnostics: install the LSP4IJ plugin, then Languages & Frameworks >")
        out.appendLine("        Language Servers > + with command: qkt lsp, and a file name pattern mapping *.qkt")
        out.appendLine("     Or build the plugin: ./gradlew -p editor/jetbrains buildPlugin")
        return EditorInstaller.InstallResult(EditorTarget.INTELLIJ, emptyList())
    }

    private companion object {
        const val PLUGIN_DIR = "qkt-jetbrains"
        const val MARKETPLACE_DOWNLOAD =
            "https://plugins.jetbrains.com/pluginManager?action=download&id=com.redhat.devtools.lsp4ij"
    }
}

/** One JetBrains IDE version's user directory, such as `IntelliJIdea2025.3`. */
data class JetBrainsIde(
    val name: String,
    val productCode: String,
    val year: Int,
    val release: Int,
    val pluginsDir: Path,
) {
    /** The qkt plugin targets platform build 242, which is IDE version 2024.2. */
    val supported: Boolean get() = year > 2024 || (year == 2024 && release >= 2)

    /** The Marketplace's name for this IDE, such as `IU-253.1`. */
    val buildCode: String get() = "$productCode-${year % 100}$release.1"
}

private fun unzip(
    zip: Path,
    into: Path,
) {
    val target = into.toAbsolutePath().normalize()
    ZipFile(zip.toFile()).use { archive ->
        for (entry in archive.entries()) {
            val file = target.resolve(entry.name).normalize()
            if (!file.startsWith(target)) throw IOException("archive entry escapes the target: ${entry.name}")
            if (entry.isDirectory) {
                Files.createDirectories(file)
            } else {
                Files.createDirectories(file.parent)
                archive.getInputStream(entry).use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
}

internal fun downloadTo(
    url: String,
    file: Path,
) {
    URI(url).toURL().openStream().use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
}
