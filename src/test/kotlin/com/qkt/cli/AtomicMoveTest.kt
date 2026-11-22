package com.qkt.cli

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AtomicMoveTest {
    @TempDir
    lateinit var dir: Path

    private val cannotMoveAtomically: (Path, Path, Array<out java.nio.file.CopyOption>) -> Unit =
        { _, _, _ -> throw AtomicMoveNotSupportedException("a", "b", "no") }

    @Test
    fun `a move replaces the target atomically where the file system can`() {
        val from = Files.writeString(dir.resolve("from"), "new")
        val to = Files.writeString(dir.resolve("to"), "old")

        moveAtomically(from, to, StandardCopyOption.REPLACE_EXISTING)

        assertThat(Files.readString(to)).isEqualTo("new")
        assertThat(from).doesNotExist()
    }

    @Test
    fun `where the file system cannot move atomically a plain move keeps the same options`() {
        val from = Files.writeString(dir.resolve("from"), "new")
        val to = Files.writeString(dir.resolve("to"), "old")

        moveAtomically(from, to, StandardCopyOption.REPLACE_EXISTING, atomicMove = cannotMoveAtomically)
        assertThat(Files.readString(to)).isEqualTo("new")

        val again = Files.writeString(dir.resolve("again"), "newer")
        assertThatThrownBy { moveAtomically(again, to, atomicMove = cannotMoveAtomically) }
            .isInstanceOf(FileAlreadyExistsException::class.java)
        assertThat(Files.readString(to)).isEqualTo("new")
    }
}
