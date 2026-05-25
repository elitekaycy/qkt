package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Heuristic detector for Neovim/Vim plugin managers.
 *
 * Used by `qkt editor install nvim|vim` to warn users who already manage
 * their editor plugins (lazy.nvim, packer, vim-plug) that a sideloaded
 * install will sit outside their plugin manager's view. Detection greps the
 * config tree for known import strings — conservative; false positives are
 * preferable to silently overwriting a managed config.
 */
class PluginManagerGuard(
    private val nvimDir: Path,
    private val vimDir: Path,
) {
    enum class Manager(
        val token: String,
        val displayName: String,
    ) {
        LAZY("lazy.nvim", "lazy.nvim"),
        PACKER("packer", "packer.nvim"),
        VIM_PLUG("vim-plug", "vim-plug"),
    }

    fun detect(target: EditorTarget): Set<Manager> =
        when (target) {
            EditorTarget.NVIM -> scan(nvimDir)
            EditorTarget.VIM -> scan(vimDir)
            else -> emptySet()
        }

    private fun scan(root: Path): Set<Manager> {
        if (!Files.isDirectory(root)) return emptySet()
        val found = mutableSetOf<Manager>()
        Files.walk(root).use { stream ->
            for (file in stream.asSequence()) {
                if (!Files.isRegularFile(file)) continue
                val name = file.fileName.toString()
                if (!(name.endsWith(".vim") || name.endsWith(".lua"))) continue
                val content =
                    try {
                        Files.readString(file)
                    } catch (_: Exception) {
                        continue
                    }
                for (m in Manager.entries) {
                    if (content.contains(m.token)) found.add(m)
                }
                if (found.size == Manager.entries.size) break
            }
        }
        return found
    }
}
