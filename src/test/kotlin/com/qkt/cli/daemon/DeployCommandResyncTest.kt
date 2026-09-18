package com.qkt.cli.daemon

import com.qkt.cli.PromotionGateConfig
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeployCommandResyncTest : DeployCommandFixture() {
    @Test
    fun `ControlClient resync replaces a deployed strategy through the daemon`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val testPlane = newPlaneWithRegistry(tmp, stateDir, PromotionGateConfig(requireApproval = false))
        val plane = testPlane.plane
        val controlClient = ControlClient(stateDir)
        val oldFile = tmp.resolve("alpha-v1.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val newFile = tmp.resolve("alpha-v2.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 2") }

        controlClient.deploy("alpha", oldFile)
        val body = controlClient.resync("alpha", newFile)

        assertThat(body).contains("\"name\":\"alpha\"")
        assertThat(body).contains("\"kind\":\"strategy\"")
        assertThat(body).contains("\"state\":\"running\"")
        assertThat(testPlane.registry.get("alpha")?.sourceFile).isEqualTo(newFile)
        waitForJournalAction(stateDir, "resync")
    }

    @Test
    fun `POST resync dry run validates without replacing the running strategy`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val testPlane = newPlaneWithRegistry(tmp, stateDir)
        val plane = testPlane.plane
        val client = OkHttpClient()
        val oldFile = tmp.resolve("alpha-v1.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val newFile = tmp.resolve("alpha-v2.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 2") }
        postDeploy(client, plane, oldFile, "alpha").close()

        val resp = postResync(client, plane, newFile, "alpha", dryRun = true)

        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"state\":\"planned\"")
        assertThat(body).contains("\"dryRun\":true")
        assertThat(testPlane.registry.get("alpha")?.sourceFile).isEqualTo(oldFile)
    }

    @Test
    fun `POST resync with an unknown name returns 404`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val testPlane = newPlaneWithRegistry(tmp, stateDir)
        val plane = testPlane.plane
        val client = OkHttpClient()
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }

        val resp = postResync(client, plane, file, "missing")

        assertThat(resp.code).isEqualTo(404)
    }

    @Test
    fun `POST resync with parse failure leaves the deployed strategy unchanged`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val testPlane = newPlaneWithRegistry(tmp, stateDir)
        val plane = testPlane.plane
        val client = OkHttpClient()
        val oldFile = tmp.resolve("alpha-v1.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val badFile = tmp.resolve("alpha-v2.qkt").also { Files.writeString(it, "not a qkt file") }
        postDeploy(client, plane, oldFile, "alpha").close()

        val resp = postResync(client, plane, badFile, "alpha")

        assertThat(resp.code).isEqualTo(400)
        assertThat(resp.body!!.string()).contains("parse failed")
        assertThat(testPlane.registry.get("alpha")?.sourceFile).isEqualTo(oldFile)
    }
}
