// Stamps version, git sha and build time into build-info.properties on the runtime
// classpath, so `qkt --version` and deploy logs name the exact build.
import java.time.Instant

plugins {
    java
}

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/build-info")

val generateBuildInfo by tasks.registering {
    val outDir = generatedResourcesDir
    val versionProvider = providers.provider { project.version.toString() }
    // Published Docker contexts exclude .git, so CI supplies qktGitSha explicitly.
    // Local source builds retain the git lookup fallback.
    val gitShaProvider =
        providers
            .gradleProperty("qktGitSha")
            .orElse(
                providers.of(GitShaSource::class) {
                    parameters.repoRoot.set(rootProject.projectDir)
                },
            )
    outputs.dir(outDir)
    inputs.property("version", versionProvider)
    inputs.property("gitSha", gitShaProvider)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        val props = dir.resolve("build-info.properties")
        val timestamp = Instant.now().toString()
        props.writeText(
            """
            |version=${versionProvider.get()}
            |gitSha=${gitShaProvider.get()}
            |buildTimestamp=$timestamp
            |
            """.trimMargin(),
        )
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedResourcesDir)
}

tasks.named("processResources") {
    dependsOn(generateBuildInfo)
}
