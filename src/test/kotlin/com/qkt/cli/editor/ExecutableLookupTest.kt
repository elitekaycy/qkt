package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExecutableLookupTest {
    @Test
    fun `windows prefers code cmd over the extensionless shell script beside it`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("code"), "#!/usr/bin/env sh\n")
        Files.writeString(tmp.resolve("code.cmd"), "@echo off\r\n")

        val found =
            findExecutable("code", mapOf("Path" to tmp.toString(), "PATHEXT" to ".COM;.EXE;.BAT;.CMD"), windows = true)

        assertThat(found).isEqualTo(tmp.resolve("code.cmd"))
    }

    @Test
    fun `windows never returns the bare extensionless name`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("npx"), "#!/bin/sh\n")

        assertThat(findExecutable("npx", mapOf("PATH" to tmp.toString()), windows = true)).isNull()
    }

    @Test
    fun `windows searches every semicolon separated entry and falls back to the default PATHEXT`(
        @TempDir tmp: Path,
    ) {
        val empty = Files.createDirectory(tmp.resolve("empty"))
        val node = Files.createDirectory(tmp.resolve("node"))
        Files.writeString(node.resolve("npx.cmd"), "@echo off\r\n")

        val found = findExecutable("npx", mapOf("PATH" to "$empty;\"$node\""), windows = true)

        assertThat(found).isEqualTo(node.resolve("npx.cmd"))
    }

    @Test
    fun `unix requires the executable bit and uses the bare name`(
        @TempDir tmp: Path,
    ) {
        val code = tmp.resolve("code")
        Files.writeString(code, "#!/bin/sh\n")
        val env = mapOf("PATH" to tmp.toString())

        code.toFile().setExecutable(false)
        val beforeChmod = findExecutable("code", env, windows = false)
        code.toFile().setExecutable(true)

        assertThat(findExecutable("code", env, windows = false)).isEqualTo(code)
        if (code.fileSystem.supportedFileAttributeViews().contains("posix")) assertThat(beforeChmod).isNull()
    }

    @Test
    fun `no PATH means nothing is found`() {
        assertThat(findExecutable("code", emptyMap(), windows = true)).isNull()
        assertThat(findExecutable("code", emptyMap(), windows = false)).isNull()
    }
}
