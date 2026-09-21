package com.qkt.cli.daemon.logging

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateDirLoggingTest {
    @Test
    fun `the state-dir flag is read in both spellings and made absolute`() {
        val absolute =
            Path
                .of("state")
                .toAbsolutePath()
                .normalize()
                .toString()

        assertThat(
            StateDirLogging.stateDirOf(arrayOf("daemon", "start", "--state-dir", "/srv/qkt")),
        ).isEqualTo("/srv/qkt")
        assertThat(StateDirLogging.stateDirOf(arrayOf("logs", "alpha", "--state-dir=/srv/qkt/"))).isEqualTo("/srv/qkt")
        assertThat(StateDirLogging.stateDirOf(arrayOf("daemon", "start", "--state-dir", "state"))).isEqualTo(absolute)
    }

    @Test
    fun `no flag, or a flag with no value, leaves the environment's directory in force`() {
        assertThat(StateDirLogging.stateDirOf(arrayOf("daemon", "start"))).isNull()
        assertThat(StateDirLogging.stateDirOf(arrayOf("daemon", "start", "--state-dir"))).isNull()
        assertThat(StateDirLogging.stateDirOf(arrayOf("daemon", "start", "--state-dir", "--json"))).isNull()
    }
}
