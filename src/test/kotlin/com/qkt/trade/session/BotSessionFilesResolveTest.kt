package com.qkt.trade.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BotSessionFilesResolveTest {
    @TempDir
    lateinit var root: Path

    private val me = ProcessHandle.current().pid()

    private fun write(
        run: String,
        pid: Long = me,
    ) = BotSessionFiles.write(root, BotSessionDescriptor(run, 4100, "tok-$run", "backtest", pid))

    @Test
    fun `the single running session is found without naming it`() {
        write("alive")
        write("dead", pid = Int.MAX_VALUE.toLong())

        val found = BotSessionFiles.resolve(root, null) { null }

        assertThat(found).isEqualTo(BotSessionDescriptor("alive", 4100, "tok-alive", "backtest", me))
    }

    @Test
    fun `two running sessions are ambiguous and fail closed`() {
        write("b-run")
        write("a-run")

        assertThatThrownBy { BotSessionFiles.resolve(root, null) { null } }
            .hasMessage("multiple bot sessions running (a-run, b-run) — pass --run <id> or set QKT_BOT_RUN")
    }

    @Test
    fun `the run named by the environment wins over discovery`() {
        write("a-run")
        write("b-run")

        val found = BotSessionFiles.resolve(root, null) { key -> if (key == "QKT_BOT_RUN") "b-run" else null }

        assertThat(found?.runId).isEqualTo("b-run")
    }

    @Test
    fun `no sessions root means no session`() {
        assertThat(BotSessionFiles.resolve(root.resolve("empty"), null) { null }).isNull()
    }

    @Test
    fun `a descriptor that does not parse reads as no session`() {
        val path = write("broken")
        Files.writeString(path, """{"runId":"broken"}""")

        assertThat(BotSessionFiles.read(root, "broken")).isNull()
        assertThat(BotSessionFiles.read(root, "never")).isNull()
    }

    @Test
    fun `the descriptor holding the token is readable by its owner alone and deleted on finish`() {
        val path = write("secret")

        assertThat(Files.getPosixFilePermissions(path))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        BotSessionFiles.delete(root, "secret")
        assertThat(path).doesNotExist()
    }
}
