package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects whether an editor is present on the current machine.
 *
 * Detection is best-effort and conservative — we only claim "detected" when
 * either the editor's CLI is on `$PATH` (for VS Code and its forks) or the config directory
 * a packaged install would manage exists (for vim-family editors).
 */
class EditorDetector(
    private val env: Map<String, String> = System.getenv(),
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val pathLookup: (String) -> Path? = ::lookupInPath,
) {
    fun detect(target: EditorTarget): Boolean =
        when (target) {
            EditorTarget.NVIM -> Files.isDirectory(nvimConfigDir())
            EditorTarget.VIM -> Files.isDirectory(vimConfigDir())
            EditorTarget.HELIX ->
                pathLookup("hx") != null ||
                    pathLookup("helix") != null ||
                    Files.isDirectory(helixConfigDir())
            EditorTarget.ZED ->
                pathLookup("zed") != null ||
                    pathLookup("zeditor") != null ||
                    Files.isDirectory(zedConfigDir())
            EditorTarget.SUBLIME -> onPath("subl") || Files.isDirectory(sublimePackagesDir())
            EditorTarget.EMACS -> onPath("emacs") || Files.isDirectory(emacsDir())
            EditorTarget.INTELLIJ ->
                JETBRAINS_CLIS.any { onPath(it) } ||
                    Files.isDirectory(configHome().resolve("JetBrains"))
            else -> extensionCli(target) != null
        }

    fun all(): Map<EditorTarget, Boolean> = EditorTarget.entries.associateWith { detect(it) }

    /** Path to the VS Code CLI (`code`, then `code-insiders`) on `$PATH`, or null. */
    fun vscodeCli(): Path? = extensionCli(EditorTarget.VSCODE)

    /** Path to the first of [target]'s extension CLIs found on `$PATH`, or null for a non-VS Code target. */
    fun extensionCli(target: EditorTarget): Path? = target.extensionClis.firstNotNullOfOrNull { pathLookup(it) }

    fun nvimConfigDir(): Path {
        val xdg = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        return (xdg ?: home.resolve(".config")).resolve("nvim")
    }

    fun vimConfigDir(): Path = home.resolve(".vim")

    /** Helix's config directory: `%AppData%\helix` on Windows, else `$XDG_CONFIG_HOME/helix` or `~/.config/helix`. */
    fun helixConfigDir(): Path = configHome().resolve("helix")

    /** Zed's config directory, found the same way as Helix's. */
    fun zedConfigDir(): Path = configHome().resolve("zed")

    /** Sublime Text's `Packages` directory (Sublime Text 4 layout on each OS). */
    fun sublimePackagesDir(): Path =
        when {
            !env["APPDATA"].isNullOrBlank() -> Path.of(env.getValue("APPDATA"), "Sublime Text", "Packages")
            Files.isDirectory(
                home.resolve("Library"),
            ) -> home.resolve("Library/Application Support/Sublime Text/Packages")
            else -> configHome().resolve("sublime-text/Packages")
        }

    /** The Emacs user directory: `~/.emacs.d` unless only `~/.config/emacs` exists. */
    fun emacsDir(): Path {
        val xdg = configHome().resolve("emacs")
        return if (!Files.isDirectory(home.resolve(".emacs.d")) &&
            Files.isDirectory(xdg)
        ) {
            xdg
        } else {
            home.resolve(".emacs.d")
        }
    }

    /** The init file Emacs reads: `~/.emacs` when it exists (Emacs then ignores init.el), else `init.el`. */
    fun emacsInitFile(): Path =
        home.resolve(".emacs").takeIf { Files.isRegularFile(it) } ?: emacsDir().resolve("init.el")

    private fun onPath(cmd: String): Boolean = pathLookup(cmd) != null

    private fun configHome(): Path {
        env["APPDATA"]?.takeIf { it.isNotBlank() && env["XDG_CONFIG_HOME"].isNullOrBlank() }?.let { return Path.of(it) }
        val xdg = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        return xdg ?: home.resolve(".config")
    }
}

private fun lookupInPath(cmd: String): Path? = findExecutable(cmd)

private val JETBRAINS_CLIS = listOf("idea", "idea.sh", "pycharm", "webstorm", "goland", "clion", "rider", "phpstorm")
