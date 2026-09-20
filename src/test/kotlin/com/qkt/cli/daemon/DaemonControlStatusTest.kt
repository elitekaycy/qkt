package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonControlStatusTest : DaemonControlFixture() {
    @Test
    fun `kill flatten returns broker verification outcome`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha"))
        handles.getValue("alpha").flattenResult =
            com.qkt.app.FlattenResult(
                verifiedFlat = false,
                remainingTickets = listOf("42"),
                detail = "ticket remains open",
            )

        val result = RegistryDaemonControl(registry).kill(Target.Strategy("alpha"), flatten = true)

        assertThat(handles.getValue("alpha").halts).containsExactly("operator kill")
        assertThat(handles.getValue("alpha").verifiedFlattens).isEqualTo(1)
        assertThat(result.flattenResults.getValue("alpha").verifiedFlat).isFalse
        assertThat(result.flattenResults.getValue("alpha").remainingTickets).containsExactly("42")
    }

    @Test
    fun `status returns one StrategyStatus per deployed strategy`(
        @TempDir tmp: Path,
    ) {
        val names = listOf("alpha", "beta")
        val (registry, handles) = registryWith(tmp, names)
        // alpha: running, not halted; beta: running, halted
        handles.getValue("beta").halt("operator")
        val report = RegistryDaemonControl(registry).status()
        assertThat(report.strategies).hasSize(2)
        val byName = report.strategies.associateBy { it.name }
        assertThat(byName.getValue("alpha").running).isTrue()
        assertThat(byName.getValue("alpha").halted).isFalse()
        assertThat(byName.getValue("beta").running).isTrue()
        assertThat(byName.getValue("beta").halted).isTrue()
    }

    @Test
    fun `status on empty registry returns empty report`(
        @TempDir tmp: Path,
    ) {
        val (registry, _) = registryWith(tmp, emptyList())
        val report = RegistryDaemonControl(registry).status()
        assertThat(report.strategies).isEmpty()
    }
}
