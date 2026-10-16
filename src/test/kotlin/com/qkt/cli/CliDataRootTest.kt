package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Every data command picks its data root the same way: `--data-root`, then `QKT_DATA_HOME`, then `data_root` in the
 * command's `qkt.config.yaml` (relative to that file), then `~/.qkt/data`. A config that names a folder qkt cannot
 * find is refused, never silently swapped for another.
 */
class CliDataRootTest {
    private fun config(
        dir: Path,
        body: String,
    ): Path = dir.resolve("qkt.config.yaml").also { Files.writeString(it, body) }

    private fun args(vararg a: String) = Args(arrayOf("data", "verify", "EURUSD", *a))

    @Test
    fun `--data-root beats the environment and the config`(
        @TempDir tmp: Path,
    ) {
        val cfg = config(tmp, "data_root: ./proj-data\n")
        val root = CliDataRoot.resolve(args("--data-root", "/x/flag"), mapOf("QKT_DATA_HOME" to "/x/env"), cfg)
        assertThat(root).isEqualTo(ResolvedDataRoot(Path.of("/x/flag"), "--data-root"))
    }

    @Test
    fun `QKT_DATA_HOME beats the config, and a blank value is ignored`(
        @TempDir tmp: Path,
    ) {
        val cfg = config(tmp, "data_root: ./proj-data\n")
        assertThat(
            CliDataRoot.resolve(args(), mapOf("QKT_DATA_HOME" to "/x/env"), cfg).path,
        ).isEqualTo(Path.of("/x/env"))
        assertThat(CliDataRoot.resolve(args(), mapOf("QKT_DATA_HOME" to "  "), cfg).path)
            .isEqualTo(tmp.resolve("proj-data").toAbsolutePath().normalize())
    }

    @Test
    fun `a relative data_root is the config file's own folder, wherever the command runs`(
        @TempDir tmp: Path,
    ) {
        val project = Files.createDirectories(tmp.resolve("proj"))
        val cfg = config(project, "source: local\ndata_root: ./data\n")
        val root = CliDataRoot.resolve(args(), emptyMap(), cfg)
        assertThat(root.path).isEqualTo(project.resolve("data").toAbsolutePath().normalize())
        assertThat(root.origin).isEqualTo("data_root in ${cfg.toAbsolutePath().normalize()}")
    }

    @Test
    fun `an existing absolute data_root is used as written`(
        @TempDir tmp: Path,
    ) {
        val store = Files.createDirectories(tmp.resolve("store"))
        val cfg = config(tmp, "data_root: $store\n")
        assertThat(CliDataRoot.resolve(args(), emptyMap(), cfg).path).isEqualTo(store)
    }

    @Test
    fun `an absolute data_root that does not exist is refused, naming the docker path`(
        @TempDir tmp: Path,
    ) {
        val cfg = config(tmp, "data_root: /qkt-test-no-such-dir/data\n")
        assertThatThrownBy { CliDataRoot.resolve(args(), emptyMap(), cfg) }
            .isInstanceOf(ArgError::class.java)
            .hasMessageContaining("is /qkt-test-no-such-dir/data, which does not exist here")
            .hasMessageContaining("pass --data-root")
    }

    @Test
    fun `a config without data_root, or no config at all, falls back to the default`(
        @TempDir tmp: Path,
    ) {
        val cfg = config(tmp, "source: local\n")
        assertThat(CliDataRoot.resolve(args(), emptyMap(), cfg).origin).isEqualTo("default")
        assertThat(CliDataRoot.resolve(args(), emptyMap(), tmp.resolve("missing.yaml")).origin).isEqualTo("default")
    }

    @Test
    fun `data_root expands environment references like the rest of the config`(
        @TempDir tmp: Path,
    ) {
        val cfg = config(tmp, "data_root: \${QKT_TEST_UNSET_DATA_VAR:-./expanded}\n")
        assertThat(CliDataRoot.resolve(args(), emptyMap(), cfg).path)
            .isEqualTo(tmp.resolve("expanded").toAbsolutePath().normalize())
    }

    @Test
    fun `the config is the one --config names`(
        @TempDir tmp: Path,
    ) {
        val cfg = config(Files.createDirectories(tmp.resolve("p")), "data_root: ./d\n")
        val root = CliDataRoot.resolve(args("--config", cfg.toString()), emptyMap())
        assertThat(root.path).isEqualTo(tmp.resolve("p/d").toAbsolutePath().normalize())
    }

    @Test
    fun `the template's data_root is the project folder on the host and the mounted folder in the container`(
        @TempDir tmp: Path,
    ) {
        val template = javaClass.getResource("/templates/minimal/qkt.config.yaml.tmpl") ?: error("missing template")
        val cfg = config(Files.createDirectories(tmp.resolve("proj")), template.readText())

        assertThat(CliDataRoot.resolve(args(), emptyMap(), cfg).path)
            .isEqualTo(tmp.resolve("proj/data").toAbsolutePath().normalize())
        // The daemon reads data_root through Config: in the container QKT_DATA_HOME=/data, as before this change.
        System.setProperty("QKT_DATA_HOME", "/data")
        try {
            assertThat(Config.load(cfg).dataRoot).isEqualTo("/data")
        } finally {
            System.clearProperty("QKT_DATA_HOME")
        }
    }
}
