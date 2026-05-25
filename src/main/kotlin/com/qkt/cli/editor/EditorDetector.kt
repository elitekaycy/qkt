package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects whether an editor is present on the current machine.
 *
 * Detection is best-effort and conservative — we only claim "detected" when
 * either the editor's CLI is on `$PATH` (for VSCode) or the config directory
 * a packaged install would manage exists (for vim-family editors).
 */
class EditorDetector(
    private val env: Map<String, String> = System.getenv(),
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name", "").lowercase(),
    private val pathLookup: (String) -> Path? = ::lookupInPath,
) {
    fun detect(target: EditorTarget): Boolean =
        when (target) {
            EditorTarget.VSCODE -> vscodeCli() != null
            EditorTarget.NVIM -> Files.isDirectory(nvimConfigDir())
            EditorTarget.VIM -> Files.isDirectory(vimConfigDir())
            EditorTarget.SUBLIME -> Files.isDirectory(sublimePackagesDir())
        }

    fun all(): Map<EditorTarget, Boolean> = EditorTarget.entries.associateWith { detect(it) }

    /** Path to a vscode-family CLI on `$PATH`, or null. Tries code, code-insiders, codium. */
    fun vscodeCli(): Path? =
        sequenceOf("code", "code-insiders", "codium").firstNotNullOfOrNull { pathLookup(it) }

    fun nvimConfigDir(): Path {
        val xdg = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        return (xdg ?: home.resolve(".config")).resolve("nvim")
    }

    fun vimConfigDir(): Path = home.resolve(".vim")

    fun sublimePackagesDir(): Path =
        if (osName.contains("mac") || osName.contains("darwin")) {
            home.resolve("Library/Application Support/Sublime Text/Packages/User")
        } else {
            home.resolve(".config/sublime-text/Packages/User")
        }
}

private fun lookupInPath(cmd: String): Path? {
    val path = System.getenv("PATH") ?: return null
    val sep = System.getProperty("path.separator") ?: ":"
    for (dir in path.split(sep).filter { it.isNotBlank() }) {
        val candidate = Path.of(dir).resolve(cmd)
        if (Files.isExecutable(candidate)) return candidate
    }
    return null
}
