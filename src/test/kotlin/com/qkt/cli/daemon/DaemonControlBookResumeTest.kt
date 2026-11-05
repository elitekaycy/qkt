package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** #1640: a persistent book halt and the children it halted are cleared from the CLI, and shown while they hold. */
class DaemonControlBookResumeTest : HaltedBookFixture() {
    @Test
    fun `resume of the book clears its halt and the halt it put on its child`(
        @TempDir tmp: Path,
    ) {
        val registry = registryWithBook(StateDir.resolve(tmp.toString()))
        haltBook()

        val result = RegistryDaemonControl(registry).resume(Target.Strategy("soak_k56"))

        assertThat(result.unknown).isEmpty()
        assertThat(result.affected).containsExactly("soak_k56")
        assertThat(result.bookHalts).containsEntry("soak_k56", reason)
        assertThat(bookRisk.halted).isFalse()
        assertThat(child.reason).isNull()
    }

    @Test
    fun `a child of a halted book is refused with the book's reason, in either spelling`(
        @TempDir tmp: Path,
    ) {
        val registry = registryWithBook(StateDir.resolve(tmp.toString()))
        haltBook()
        val control = RegistryDaemonControl(registry)

        for (name in listOf("soak_k56/coin", "soak_k56:coin")) {
            val result = control.resume(Target.Strategy(name))

            assertThat(result.unknown).isEmpty()
            assertThat(result.affected).isEmpty()
            assertThat(result.refused.getValue("soak_k56/coin")).contains(reason).contains("qkt resume soak_k56")
        }
        assertThat(child.reason).isEqualTo(reason)
        assertThat(bookRisk.halted).isTrue()
    }

    @Test
    fun `a child of a book that trades is resumed by either spelling`(
        @TempDir tmp: Path,
    ) {
        val registry = registryWithBook(StateDir.resolve(tmp.toString()))
        child.halt("engine fault")

        val result = RegistryDaemonControl(registry).resume(Target.Strategy("soak_k56:coin"))

        assertThat(result.affected).containsExactly("soak_k56/coin")
        assertThat(child.reason).isNull()
    }

    @Test
    fun `resume all clears book halts too`(
        @TempDir tmp: Path,
    ) {
        val registry = registryWithBook(StateDir.resolve(tmp.toString()))
        haltBook()

        val result = RegistryDaemonControl(registry).resume(Target.All)

        assertThat(result.affected).containsExactlyInAnyOrder("soak_k56", "soak_k56/coin")
        assertThat(bookRisk.halted).isFalse()
        assertThat(child.reason).isNull()
    }

    @Test
    fun `a book resume is journaled with the halt it cleared`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry = registryWithBook(stateDir)
        haltBook()

        RegistryDaemonControl(registry, OperatorJournal(stateDir, "test")).resume(Target.Strategy("soak_k56"))

        val journal =
            Files
                .walk(stateDir.stateRoot.resolve("journal/soak_k56"))
                .filter { Files.isRegularFile(it) }
                .findFirst()
                .orElseThrow()
                .let(Files::readString)
        assertThat(journal).contains("\"action\":\"resume\"").contains("\"outcome\":\"accepted\"")
        assertThat(journal).contains("\"bookHalts\":\"soak_k56: portfolio risk evaluation failed")
    }

    @Test
    fun `list and status show the halted book with its reason, and the routes resume it`(
        @TempDir tmp: Path,
    ) {
        val registry = registryWithBook(StateDir.resolve(tmp.toString()))
        haltBook()
        val plane = ControlPlane(registry, port = 0).also { it.start() }
        val http = OkHttpClient()
        val base = "http://127.0.0.1:${plane.boundPort}"

        fun get(path: String) = http.newCall(Request.Builder().url(base + path).build()).execute()

        fun post(path: String) =
            http
                .newCall(
                    Request
                        .Builder()
                        .url(base + path)
                        .post("".toRequestBody("text/plain".toMediaType()))
                        .build(),
                ).execute()
        try {
            val list = get("/list").body!!.string()
            assertThat(list).contains(""""name":"soak_k56","kind":"portfolio"""")
            assertThat(list).contains(""""halted":true,"haltReason":"$reason","haltScope":"PERSISTENT"""")
            assertThat(get("/status/soak_k56").body!!.string())
                .contains(""""halted":true""")
                .contains(""""haltReason":"$reason"""")

            val refused = post("/resume/soak_k56:coin")
            assertThat(refused.code).isEqualTo(409)
            assertThat(refused.body!!.string()).contains("portfolio soak_k56 is halted (PERSISTENT)")

            val resumed = post("/resume/soak_k56")
            assertThat(resumed.code).isEqualTo(200)
            assertThat(resumed.body!!.string()).contains(""""bookHalts":{"soak_k56":"$reason"}""")
            assertThat(get("/list").body!!.string()).doesNotContain("haltReason")
        } finally {
            plane.close()
        }
    }
}
