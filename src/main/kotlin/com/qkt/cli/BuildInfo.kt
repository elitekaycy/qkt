package com.qkt.cli

import java.util.Properties

/**
 * Build metadata produced by the Gradle `generateBuildInfo` task and packaged
 * as `/build-info.properties`. Single source of truth for the version string,
 * git SHA, and build timestamp — kept in sync with the repo-root `VERSION` file
 * and `git rev-parse HEAD` at build time.
 *
 * `qkt --version` prints all three so operators can answer "what's deployed"
 * by reading the CLI output, not by SSH-ing into the box.
 */
object BuildInfo {
    private val props: Properties =
        Properties().apply {
            BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
        }

    val VERSION: String = props.getProperty("version") ?: "unknown"
    val GIT_SHA: String = props.getProperty("gitSha") ?: "unknown"
    val BUILD_TIMESTAMP: String = props.getProperty("buildTimestamp") ?: "unknown"

    fun versionLine(): String = "qkt $VERSION ($GIT_SHA) built $BUILD_TIMESTAMP"
}
