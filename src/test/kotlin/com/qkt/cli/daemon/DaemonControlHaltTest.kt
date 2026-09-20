package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonControlHaltTest : DaemonControlFixture() {
    @Test
    fun `halt All halts every strategy`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).halt(Target.All)
        assertThat(result.affected).containsExactlyInAnyOrder("alpha", "beta")
        assertThat(result.unknown).isEmpty()
        assertThat(handles.getValue("alpha").halts).hasSize(1)
        assertThat(handles.getValue("beta").halts).hasSize(1)
    }

    @Test
    fun `halt one strategy halts only it`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).halt(Target.Strategy("alpha"))
        assertThat(result.affected).containsExactly("alpha")
        assertThat(handles.getValue("alpha").halts).hasSize(1)
        assertThat(handles.getValue("beta").halts).isEmpty()
    }

    @Test
    fun `operator halt and resume are journaled`(
        @TempDir tmp: Path,
    ) {
        val (registry, _) = registryWith(tmp, listOf("alpha"))
        val stateDir = StateDir.resolve(tmp.resolve("qkt-state").toString())
        val control = RegistryDaemonControl(registry, OperatorJournal(stateDir, "test"))

        control.halt(Target.Strategy("alpha"))
        control.resume(Target.Strategy("alpha"))

        val journal =
            Files
                .walk(stateDir.stateRoot.resolve("journal/alpha"))
                .filter { Files.isRegularFile(it) }
                .findFirst()
                .orElseThrow()
                .let(Files::readString)
        assertThat(journal).contains("\"kind\":\"operator_action\"")
        assertThat(journal).contains("\"action\":\"halt\"")
        assertThat(journal).contains("\"action\":\"resume\"")
        assertThat(journal).contains("\"target\":\"alpha\"")
        assertThat(journal).contains("\"source\":\"test\"")
        assertThat(journal).contains("\"outcome\":\"accepted\"")
    }

    @Test
    fun `halt unknown name reports it and changes nothing`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha"))
        val result = RegistryDaemonControl(registry).halt(Target.Strategy("ghost"))
        assertThat(result.affected).isEmpty()
        assertThat(result.unknown).containsExactly("ghost")
        assertThat(handles.getValue("alpha").halts).isEmpty()
    }

    @Test
    fun `resume All resumes every strategy`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).resume(Target.All)
        assertThat(result.affected).containsExactlyInAnyOrder("alpha", "beta")
        assertThat(handles.getValue("alpha").resumes).isEqualTo(1)
        assertThat(handles.getValue("beta").resumes).isEqualTo(1)
    }
}
