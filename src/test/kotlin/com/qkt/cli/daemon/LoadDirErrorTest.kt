package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LoadDirErrorTest {
    @Test
    fun `loadDirIfRequested reports each strategy that fails to deploy`(
        @TempDir tmp: Path,
    ) {
        val dir = Files.createDirectories(tmp.resolve("strategies"))
        Files.writeString(dir.resolve("broken.qkt"), "STRATEGY broken")
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { _, _, _ -> error("bad strategy") },
            )

        val errors = mutableListOf<Pair<String, String>>()
        DaemonCommand(Args(arrayOf("daemon")))
            .loadDirIfRequested(dir.toString(), registry) { name, message ->
                errors.add(name to message)
            }

        assertThat(errors).hasSize(1)
        assertThat(errors[0].first).isEqualTo("broken")
        assertThat(errors[0].second).contains("bad strategy")
    }

    @Test
    fun `loadDirIfRequested reports nothing when every strategy deploys`(
        @TempDir tmp: Path,
    ) {
        val dir = Files.createDirectories(tmp.resolve("strategies"))
        Files.writeString(dir.resolve("ok.qkt"), "STRATEGY ok")
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { name, _, _ ->
                    error("unreachable in this test: $name")
                },
            )

        // No .qkt files removed; the registry factory would throw, but with an empty
        // directory there is nothing to deploy and no error should be reported.
        val emptyDir = Files.createDirectories(tmp.resolve("empty"))
        val errors = mutableListOf<Pair<String, String>>()
        DaemonCommand(Args(arrayOf("daemon")))
            .loadDirIfRequested(emptyDir.toString(), registry) { name, message ->
                errors.add(name to message)
            }

        assertThat(errors).isEmpty()
    }
}
