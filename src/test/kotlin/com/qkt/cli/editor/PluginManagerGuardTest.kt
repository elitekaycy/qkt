package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginManagerGuardTest {
    @Test
    fun `nothing detected when nvim dir does not exist`(
        @TempDir tmp: Path,
    ) {
        val guard = PluginManagerGuard(nvimDir = tmp.resolve("missing-nvim"), vimDir = tmp.resolve("missing-vim"))
        assertThat(guard.detect(EditorTarget.NVIM)).isEmpty()
        assertThat(guard.detect(EditorTarget.VIM)).isEmpty()
    }

    @Test
    fun `lazy-dot-nvim mention in init-dot-lua is detected`(
        @TempDir tmp: Path,
    ) {
        val nvim = tmp.resolve("nvim").also { Files.createDirectories(it) }
        Files.writeString(
            nvim.resolve("init.lua"),
            """
            local lazypath = vim.fn.stdpath("data") .. "/lazy/lazy.nvim"
            require("lazy").setup({})
            """.trimIndent(),
        )
        val guard = PluginManagerGuard(nvimDir = nvim, vimDir = tmp.resolve(".vim"))
        assertThat(guard.detect(EditorTarget.NVIM)).containsExactly(PluginManagerGuard.Manager.LAZY)
    }

    @Test
    fun `packer mention in lua file is detected`(
        @TempDir tmp: Path,
    ) {
        val nvim = tmp.resolve("nvim").also { Files.createDirectories(it) }
        Files.createDirectories(nvim.resolve("lua"))
        Files.writeString(
            nvim.resolve("lua/plugins.lua"),
            """return require("packer").startup(function(use) end)""",
        )
        val guard = PluginManagerGuard(nvimDir = nvim, vimDir = tmp.resolve(".vim"))
        assertThat(guard.detect(EditorTarget.NVIM)).contains(PluginManagerGuard.Manager.PACKER)
    }

    @Test
    fun `vim-plug mention in vimrc-style file is detected`(
        @TempDir tmp: Path,
    ) {
        val vim = tmp.resolve(".vim").also { Files.createDirectories(it) }
        Files.writeString(
            vim.resolve("plugins.vim"),
            """
            call plug#begin()
            " vim-plug declarations
            call plug#end()
            """.trimIndent(),
        )
        val guard = PluginManagerGuard(nvimDir = tmp.resolve("nvim"), vimDir = vim)
        assertThat(guard.detect(EditorTarget.VIM)).contains(PluginManagerGuard.Manager.VIM_PLUG)
    }

    @Test
    fun `multiple managers in the same tree are all reported`(
        @TempDir tmp: Path,
    ) {
        val nvim = tmp.resolve("nvim").also { Files.createDirectories(it) }
        Files.writeString(nvim.resolve("a.lua"), """local lazypath = "/path/to/lazy/lazy.nvim"""")
        Files.writeString(nvim.resolve("b.lua"), """require("packer").startup()""")
        val guard = PluginManagerGuard(nvimDir = nvim, vimDir = tmp.resolve(".vim"))
        val detected = guard.detect(EditorTarget.NVIM)
        assertThat(detected).contains(PluginManagerGuard.Manager.LAZY, PluginManagerGuard.Manager.PACKER)
    }

    @Test
    fun `non-vim-or-lua files are ignored`(
        @TempDir tmp: Path,
    ) {
        val nvim = tmp.resolve("nvim").also { Files.createDirectories(it) }
        Files.writeString(nvim.resolve("notes.md"), "this file mentions lazy.nvim and packer")
        val guard = PluginManagerGuard(nvimDir = nvim, vimDir = tmp.resolve(".vim"))
        assertThat(guard.detect(EditorTarget.NVIM)).isEmpty()
    }

    @Test
    fun `vscode and sublime targets return empty regardless of config`(
        @TempDir tmp: Path,
    ) {
        val guard = PluginManagerGuard(nvimDir = tmp, vimDir = tmp)
        assertThat(guard.detect(EditorTarget.VSCODE)).isEmpty()
        assertThat(guard.detect(EditorTarget.SUBLIME)).isEmpty()
    }
}
