package com.qkt.cli.editor

/**
 * Editors the `qkt editor` subcommand knows how to install plugins for.
 *
 * Aliases recognized on the command line are listed in [parse]. The display
 * name is what `qkt editor list` prints.
 */
enum class EditorTarget(
    val displayName: String,
    val cliName: String,
) {
    VSCODE("VSCode", "vscode"),
    NVIM("Neovim", "nvim"),
    VIM("Vim", "vim"),
    SUBLIME("Sublime Text", "sublime"),
    ;

    companion object {
        fun parse(s: String): EditorTarget? =
            when (s.lowercase()) {
                "vscode", "code", "vs-code" -> VSCODE
                "nvim", "neovim" -> NVIM
                "vim" -> VIM
                "sublime", "sublime-text", "subl" -> SUBLIME
                else -> null
            }
    }
}
