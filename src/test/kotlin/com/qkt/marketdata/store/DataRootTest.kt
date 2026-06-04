package com.qkt.marketdata.store

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DataRootTest {
    @Test
    fun `falls back to user home dotqkt data when env unset`() {
        val resolved = DataRoot.resolveExplicit(env = null)
        val expected = Path.of(System.getProperty("user.home"), ".qkt", "data")
        assertThat(resolved).isEqualTo(expected)
    }

    @Test
    fun `respects QKT_DATA_HOME when set`() {
        val resolved = DataRoot.resolveExplicit(env = "/tmp/qkt-test")
        assertThat(resolved).isEqualTo(Path.of("/tmp/qkt-test"))
    }

    @Test
    fun `forDataRoot prefers an explicit --data-root flag`() {
        assertThat(DataRoot.forDataRoot("./mydata")).isEqualTo(Path.of("./mydata"))
    }

    @Test
    fun `forDataRoot falls back to the env-or-default root when no flag`() {
        assertThat(DataRoot.forDataRoot(null)).isEqualTo(DataRoot.resolve())
    }
}
