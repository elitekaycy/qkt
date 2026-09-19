package com.qkt.cli.editor

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
    private val vscode =
        VscodeExtensionInstaller(detector, manifestPath, out, err, processRunner, pathLookup)

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
                EditorTarget.VSCODE -> vscode.install(root)
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
        if (target == EditorTarget.VSCODE) return vscode.uninstall()
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
