package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs Sublime Text and Emacs support by copying bundled files into the editor's own
 * directories. Emacs also gets a [ManagedBlock] in its init file that loads `qkt-mode`.
 * Neither editor ships an LSP client we can configure without touching the user's settings,
 * so Sublime prints the LSP package snippet; Emacs uses eglot, which `qkt-mode` starts itself.
 */
internal class FileCopyInstaller(
    private val detector: EditorDetector,
    private val out: Appendable,
    private val err: Appendable,
) {
    /** Copies the Sublime grammar and comment settings into `Packages/User/qkt`. */
    fun installSublime(root: Path): EditorInstaller.InstallResult? {
        val src = root.resolve("sublime")
        val dst = detector.sublimePackagesDir().resolve("User/qkt")
        val placed = copy(src, dst, listOf("qkt.tmLanguage", "Comments.tmPreferences")) ?: return null
        out.appendLine("qkt editor: installed Sublime Text syntax (${placed.size} files at $dst)")
        out.appendLine("     For completion and diagnostics, install the LSP package and add to LSP.sublime-settings:")
        out.appendLine(
            """       "clients": { "qkt": { "enabled": true, "command": ["qkt", "lsp"], "selector": "source.qkt" } }""",
        )
        return EditorInstaller.InstallResult(EditorTarget.SUBLIME, placed)
    }

    /** Copies `qkt-mode.el` into the Emacs directory and loads it from the init file. */
    fun installEmacs(root: Path): EditorInstaller.InstallResult? {
        val dir = detector.emacsDir().resolve("qkt")
        val placed = copy(root.resolve("emacs"), dir, listOf("qkt-mode.el")) ?: return null
        val path = dir.toAbsolutePath().toString().replace("\\", "/")
        emacsBlock.write(detector.emacsInitFile(), "(add-to-list 'load-path \"$path\")\n(require 'qkt-mode)")
        out.appendLine("qkt editor: installed Emacs qkt-mode at $dir (loaded from ${detector.emacsInitFile()})")
        return EditorInstaller.InstallResult(EditorTarget.EMACS, placed)
    }

    /** Removes the init-file block that loads `qkt-mode`. */
    fun removeEmacsConfig() = emacsBlock.remove(detector.emacsInitFile())

    private val emacsBlock = ManagedBlock(";;", EditorTarget.EMACS)

    private fun copy(
        src: Path,
        dst: Path,
        names: List<String>,
    ): List<Path>? {
        if (names.any { !Files.isRegularFile(src.resolve(it)) }) {
            err.appendLine("qkt: editor files not found at $src")
            return null
        }
        Files.createDirectories(dst)
        return names.map { name ->
            dst.resolve(name).also { Files.copy(src.resolve(name), it, StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
