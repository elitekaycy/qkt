package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Windows paths carry backslashes, which are escapes in JSON. Deploy and resync must send them as
 * JSON strings, and route errors that echo a path must still be valid JSON.
 */
class ControlJsonPathTest : DeployCommandFixture() {
    @Test
    fun `deploy sends a path with backslashes as valid json`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.resolve("state").toString())
        val plane = newPlane(tmp, stateDir)
        val dir = Files.createDirectories(tmp.resolve("C:\\Users\\trader\\strats"))
        val file = dir.resolve("foo.qkt").also { Files.writeString(it, "STRATEGY x VERSION 1") }

        val body = ControlClient(stateDir, explicitPort = plane.boundPort).deploy(name = "foo", file = file)

        val obj = Json.parseToJsonElement(body).jsonObject
        assertThat(obj["name"]!!.jsonPrimitive.content).isEqualTo("foo")
        assertThat(obj["state"]!!.jsonPrimitive.content).isEqualTo("running")
    }

    @Test
    fun `resync dry run sends a path with backslashes as valid json`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.resolve("state").toString())
        val plane = newPlane(tmp, stateDir)
        val dir = Files.createDirectories(tmp.resolve("C:\\Users\\trader\\strats"))
        val file = dir.resolve("foo.qkt").also { Files.writeString(it, "STRATEGY x VERSION 1") }
        val client = ControlClient(stateDir, explicitPort = plane.boundPort)
        client.deploy(name = "foo", file = file)

        val body = client.resync(name = "foo", file = file, dryRun = true)

        assertThat(
            Json
                .parseToJsonElement(body)
                .jsonObject["dryRun"]!!
                .jsonPrimitive.content,
        ).isEqualTo("true")
    }

    @Test
    fun `an error that echoes a windows path is valid json`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.resolve("state").toString())
        val plane = newPlane(tmp, stateDir)
        val missing = tmp.resolve("C:\\nope\\missing.qkt")

        val error =
            catchThrowableOfType(ControlClient.DaemonError::class.java) {
                ControlClient(stateDir, explicitPort = plane.boundPort).deploy(name = "foo", file = missing)
            }

        assertThat(error.code).isEqualTo(400)
        val message =
            Json
                .parseToJsonElement(error.body)
                .jsonObject["error"]!!
                .jsonPrimitive.content
        assertThat(message).isEqualTo("file not found: ${missing.toAbsolutePath()}")
    }
}
