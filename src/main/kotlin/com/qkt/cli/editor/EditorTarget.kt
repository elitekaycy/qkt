package com.qkt.cli.editor

/**
 * Editors the `qkt editor` subcommand knows how to install plugins for.
 *
 * Aliases recognized on the command line are listed in [parse]. The display
 * name is what `qkt editor list` prints. [extensionClis] is non-empty for the
 * VS Code family: the commands, tried in order, that accept
 * `--install-extension`. They all take the same `.vsix`.
 */
enum class EditorTarget(
    val displayName: String,
    val cliName: String,
    val extensionClis: List<String> = emptyList(),
) {
    VSCODE("VSCode", "vscode", listOf("code", "code-insiders")),
    CURSOR("Cursor", "cursor", listOf("cursor")),
    WINDSURF("Windsurf", "windsurf", listOf("windsurf")),
    VSCODIUM("VSCodium", "codium", listOf("codium", "codium-insiders")),
    NVIM("Neovim", "nvim"),
    VIM("Vim", "vim"),
    HELIX("Helix", "helix"),
    ZED("Zed", "zed"),
    ;

    /** True for VS Code and its forks, which install the bundled `.vsix`. */
    val isVscodeFamily: Boolean get() = extensionClis.isNotEmpty()

    companion object {
        /** Every target's [cliName], for usage messages. */
        val cliNames: String get() = entries.joinToString(", ") { it.cliName }

        fun parse(s: String): EditorTarget? =
            when (s.lowercase()) {
                "vscode", "code", "vs-code" -> VSCODE
                "cursor" -> CURSOR
                "windsurf" -> WINDSURF
                "codium", "vscodium" -> VSCODIUM
                "nvim", "neovim" -> NVIM
                "vim" -> VIM
                "helix", "hx" -> HELIX
                "zed" -> ZED
                else -> null
            }
    }
}
