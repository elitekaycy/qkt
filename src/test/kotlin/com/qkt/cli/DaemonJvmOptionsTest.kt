package com.qkt.cli

import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * #1645: `qkt daemon` ran on the JVM's default heap (25% of the container limit) and kept a thrashing process alive
 * after an OutOfMemoryError. The launcher snippet the start script carries (scripts/launcher/daemon-jvm.sh) gives the
 * daemon a heap sized from the limit, one heap dump in the state directory and an exit on OOM, all overridable.
 */
@DisabledOnOs(OS.WINDOWS)
class DaemonJvmOptionsTest {
    private val snippet = File("scripts/launcher/daemon-jvm.sh").canonicalFile

    /** The JVM arguments the start script builds for `qkt <command>`, split the way it splits them. */
    private fun jvmArgs(
        command: String,
        env: Map<String, String> = emptyMap(),
    ): List<String> {
        val script =
            "DEFAULT_JVM_OPTS='\"-XX:+UseParallelGC\"'; . '$snippet'; " +
                "printf '%s\\n' \"\$DEFAULT_JVM_OPTS \$JAVA_OPTS \$QKT_OPTS\" | xargs -n1"
        return run(listOf("sh", "-c", script, "sh", command), env).filter { it.isNotBlank() }
    }

    private fun run(
        cmd: List<String>,
        env: Map<String, String>,
    ): List<String> {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().remove("JAVA_OPTS")
        pb.environment().remove("QKT_OPTS")
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readLines()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "timed out: $cmd" }
        return out
    }

    /** The final value of each named JVM flag when started with [args]. */
    private fun finalFlags(args: List<String>): Map<String, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        return run(listOf(java) + args + listOf("-XX:+PrintFlagsFinal", "-version"), emptyMap())
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[2] == "=") parts[1] to parts[3] else null
            }.toMap()
    }

    @Test
    fun `the daemon gets a heap from the container limit, a heap dump in the state dir and an exit on OOM`(
        @TempDir state: File,
    ) {
        val args = jvmArgs("daemon", mapOf("QKT_STATE_DIR" to state.path))

        assertThat(args).containsExactly(
            "-XX:+UseParallelGC",
            "-XX:MaxRAMPercentage=70",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=${state.path}/daemon-oom.hprof",
            "-XX:+ExitOnOutOfMemoryError",
        )
        val flags = finalFlags(args)
        assertThat(flags["MaxRAMPercentage"]).startsWith("70.0")
        assertThat(flags["ExitOnOutOfMemoryError"]).isEqualTo("true")
        assertThat(flags["HeapDumpOnOutOfMemoryError"]).isEqualTo("true")
    }

    @Test
    fun `JAVA_OPTS and QKT_OPTS still override the daemon defaults`() {
        val args =
            jvmArgs(
                "daemon",
                mapOf("JAVA_OPTS" to "-XX:MaxRAMPercentage=50", "QKT_OPTS" to "-XX:-ExitOnOutOfMemoryError"),
            )

        val flags = finalFlags(args)
        assertThat(flags["MaxRAMPercentage"]).startsWith("50.0")
        assertThat(flags["ExitOnOutOfMemoryError"]).isEqualTo("false")
    }

    @Test
    fun `other commands keep the JVM defaults`() {
        assertThat(jvmArgs("backtest")).containsExactly("-XX:+UseParallelGC")
    }
}
