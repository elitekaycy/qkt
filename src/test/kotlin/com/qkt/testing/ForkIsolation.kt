package com.qkt.testing

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.nio.file.Path

/**
 * Gives each Gradle test JVM its own backtest runs directory, so parallel forks (`maxParallelForks`) never write
 * into one another's bundles. Gradle names each fork in `org.gradle.test.worker`; the build points `qkt.runs.home`
 * at build/test-runs, and every fork moves to build/test-runs/fork-<worker> before any test runs. Registered through
 * META-INF/services, so it runs once per test JVM.
 */
class ForkIsolation : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession) {
        val worker = System.getProperty("org.gradle.test.worker") ?: return
        val runsHome = System.getProperty("qkt.runs.home") ?: return
        System.setProperty("qkt.runs.home", Path.of(runsHome, "fork-$worker").toString())
    }
}
