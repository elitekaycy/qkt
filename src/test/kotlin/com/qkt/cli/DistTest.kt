package com.qkt.cli

import java.io.File
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DistTest {
    @Test
    fun `installDist produces a runnable bin qkt that prints version`() {
        val projectRoot = File(".").canonicalFile
        val gradlew = File(projectRoot, "gradlew")
        require(gradlew.exists()) { "gradlew not found at ${gradlew.absolutePath}" }

        val install =
            ProcessBuilder(gradlew.absolutePath, "installDist", "--quiet")
                .directory(projectRoot)
                .inheritIO()
                .start()
        check(install.waitFor(5, TimeUnit.MINUTES)) { "gradlew installDist timed out" }
        check(install.exitValue() == 0) { "gradlew installDist failed" }

        val launcher = File(projectRoot, "build/install/qkt/bin/qkt")
        assertThat(launcher).exists()
        assertThat(launcher.canExecute()).isTrue

        val proc =
            ProcessBuilder(launcher.absolutePath, "--version")
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
        check(proc.waitFor(60, TimeUnit.SECONDS)) { "qkt --version timed out" }
        val output =
            proc.inputStream
                .bufferedReader()
                .readText()
                .trim()
        assertThat(proc.exitValue()).isEqualTo(0)
        assertThat(output).contains("qkt ${BuildInfo.VERSION}")
    }
}
