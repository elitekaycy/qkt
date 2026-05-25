package com.qkt.cli.daemon

import com.qkt.cli.BuildInfo
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("dockerSmoke")
class DockerImageTest {
    @Test
    fun `qkt --version inside container returns matching version`() {
        // Assumes ./gradlew dockerBuild has been run before invoking this test.
        val process =
            ProcessBuilder("docker", "run", "--rm", "qkt:local", "--version")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        assertThat(process.exitValue()).withFailMessage("output=$output").isEqualTo(0)
        assertThat(output.trim()).startsWith("qkt ${BuildInfo.VERSION} (")
        assertThat(output.trim()).matches("qkt \\S+ \\(\\S+\\) built \\S+")
    }
}
