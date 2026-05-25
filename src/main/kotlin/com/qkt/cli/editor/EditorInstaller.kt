package com.qkt.cli.editor

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs the bundled editor integrations on the user's machine.
 *
 * Per-target outcome captured in [InstallResult] so the caller (and the
 * eventual manifest writer) knows exactly which files were placed.
 *
 * The [processRunner] and [pathLookup] seams keep the installer testable —
 * unit tests inject record-and-replay implementations; production wiring uses
 * [runProcess] (inherits stdio) and [defaultPathLookup] (real `$PATH`).
 */
class EditorInstaller(
    private val detector: EditorDetector = EditorDetector(),
    private val editorRoot: Path? = EditorPaths.bundledEditorRoot(),
    private val manifestPath: Path = EditorManifest.defaultPath(),
    private val out: Appendable = System.out,
    private val err: Appendable = System.err,
    private val processRunner: (List<String>, Path?) -> Int = ::runProcess,
    private val pathLookup: (String) -> Path? = ::defaultPathLookup,
) {
    /** Outcome of a single-target install. `placedFiles` feeds the uninstall manifest. */
    data class InstallResult(
        val target: EditorTarget,
        val placedFiles: List<Path>,
    )

    /** Installs [target] or returns null if the prerequisites aren't met. Updates the manifest on success. */
    fun install(target: EditorTarget): InstallResult? {
        val root =
            editorRoot ?: run {
                err.appendLine("qkt: cannot locate bundled editor files (set QKT_HOME or run from a packaged install)")
                return null
            }
        val result =
            when (target) {
                EditorTarget.VSCODE -> installVscode(root)
                EditorTarget.NVIM -> installVimFamily(root, detector.nvimConfigDir(), EditorTarget.NVIM)
                EditorTarget.VIM -> installVimFamily(root, detector.vimConfigDir(), EditorTarget.VIM)
                EditorTarget.SUBLIME -> installSublime(root, detector.sublimePackagesDir())
            } ?: return null
        val updated = EditorManifest.load(manifestPath).withInstall(target, result.placedFiles)
        EditorManifest.save(manifestPath, updated)
        return result
    }

    /** Removes whatever the manifest says we placed for [target]. Returns true on success. */
    fun uninstall(target: EditorTarget): Boolean {
        if (target == EditorTarget.VSCODE) return uninstallVscode()
        val manifest = EditorManifest.load(manifestPath)
        val entry =
            manifest.recordFor(target) ?: run {
                err.appendLine(
                    "qkt: no install record for ${target.displayName} — refusing to remove files " +
                        "qkt did not place. Run `qkt editor install ${target.cliName}` first.",
                )
                return false
            }
        var removed = 0
        for (f in entry.files) {
            val p = Path.of(f)
            if (Files.deleteIfExists(p)) removed++
        }
        EditorManifest.save(manifestPath, manifest.withoutInstall(target))
        out.appendLine("qkt editor: removed ${target.displayName} plugin ($removed files)")
        return true
    }

    private fun uninstallVscode(): Boolean {
        val cli =
            detector.vscodeCli() ?: run {
                err.appendLine("qkt: vscode 'code' command not on PATH — cannot uninstall the extension.")
                return false
            }
        val rc = processRunner(listOf(cli.toString(), "--uninstall-extension", VSCODE_EXTENSION_ID), null)
        if (rc != 0) {
            err.appendLine("qkt: vscode uninstall failed (exit $rc)")
            return false
        }
        val manifest = EditorManifest.load(manifestPath).withoutInstall(EditorTarget.VSCODE)
        EditorManifest.save(manifestPath, manifest)
        out.appendLine("qkt editor: removed VSCode extension $VSCODE_EXTENSION_ID")
        return true
    }

    private fun installVscode(root: Path): InstallResult? {
        val cli =
            detector.vscodeCli() ?: run {
                err.appendLine("qkt: vscode 'code' command not on PATH — install VSCode first, then re-run.")
                return null
            }
        val vscodeRoot = root.resolve("vscode")
        if (!Files.isDirectory(vscodeRoot)) {
            err.appendLine("qkt: editor source for VSCode not found at $vscodeRoot")
            return null
        }
        val vsix =
            findExistingVsix(vscodeRoot)
                ?: buildVsixFromSource(vscodeRoot)
                ?: run {
                    err.appendLine(
                        "qkt: no .vsix bundled and could not build one (vsce/npx not on PATH).",
                    )
                    err.appendLine(
                        "     Download the latest .vsix from: https://github.com/elitekaycy/qkt/releases/latest",
                    )
                    return null
                }
        val rc = processRunner(listOf(cli.toString(), "--install-extension", vsix.toString()), null)
        if (rc != 0) {
            err.appendLine("qkt: vscode install failed (exit $rc)")
            return null
        }
        out.appendLine("qkt editor: installed VSCode extension from $vsix")
        // VSCode owns the installed extension under its own extensions dir;
        // nothing for the uninstall manifest to track at filesystem level.
        return InstallResult(EditorTarget.VSCODE, emptyList())
    }

    private fun findExistingVsix(vscodeRoot: Path): Path? =
        Files.list(vscodeRoot).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".vsix") }
                .findFirst()
                .orElse(null)
        }

    private fun buildVsixFromSource(vscodeRoot: Path): Path? {
        val npx = pathLookup("npx") ?: return null
        val rc =
            processRunner(
                listOf(
                    npx.toString(),
                    "--yes",
                    "@vscode/vsce@latest",
                    "package",
                    "--no-dependencies",
                ),
                vscodeRoot,
            )
        if (rc != 0) return null
        return findExistingVsix(vscodeRoot)
    }

    private fun installVimFamily(
        root: Path,
        configDir: Path,
        target: EditorTarget,
    ): InstallResult? {
        val src = root.resolve("nvim")
        if (!Files.isDirectory(src)) {
            err.appendLine("qkt: editor source for ${target.displayName} not found at $src")
            return null
        }
        val placed = mutableListOf<Path>()
        for (sub in listOf("ftdetect", "ftplugin", "syntax")) {
            val srcFile = src.resolve(sub).resolve("qkt.vim")
            if (!Files.isRegularFile(srcFile)) continue
            val dstDir = configDir.resolve(sub)
            Files.createDirectories(dstDir)
            val dstFile = dstDir.resolve("qkt.vim")
            Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING)
            placed.add(dstFile)
        }
        out.appendLine("qkt editor: installed ${target.displayName} plugin (${placed.size} files at $configDir)")
        return InstallResult(target, placed)
    }

    private fun installSublime(
        root: Path,
        packagesDir: Path,
    ): InstallResult? {
        val src = root.resolve("textmate").resolve("qkt.tmLanguage.json")
        if (!Files.isRegularFile(src)) {
            err.appendLine("qkt: textmate grammar not found at $src")
            return null
        }
        Files.createDirectories(packagesDir)
        val dst = packagesDir.resolve("qkt.sublime-syntax")
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        out.appendLine("qkt editor: installed Sublime grammar at $dst")
        return InstallResult(EditorTarget.SUBLIME, listOf(dst))
    }
}

private const val VSCODE_EXTENSION_ID = "elitekaycy.qkt"

private fun runProcess(
    cmd: List<String>,
    workingDir: Path?,
): Int =
    try {
        val pb = ProcessBuilder(cmd).inheritIO()
        if (workingDir != null) pb.directory(workingDir.toFile())
        pb.start().waitFor()
    } catch (_: IOException) {
        -1
    }

private fun defaultPathLookup(name: String): Path? {
    val path = System.getenv("PATH") ?: return null
    val sep = System.getProperty("path.separator") ?: ":"
    for (dir in path.split(sep).filter { it.isNotBlank() }) {
        val c = Path.of(dir).resolve(name)
        if (Files.isExecutable(c)) return c
    }
    return null
}
