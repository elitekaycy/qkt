// Test coverage (Kover). `koverXmlReport`/`koverHtmlReport` write the reports CI publishes and
// the PR diff gate reads; `koverVerify` fails `check` when total line or branch coverage drops
// below the floor in config/coverage-floor.properties. The floor only ratchets up.
plugins {
    id("org.jetbrains.kotlinx.kover")
}

val floor =
    java.util.Properties().apply {
        rootProject.file("config/coverage-floor.properties").inputStream().use { load(it) }
    }

kover {
    reports {
        filters {
            excludes {
                annotatedBy("com.qkt.common.coverage.ExcludeFromCoverage")
                // Hand-run harnesses registered in qkt.dev-tasks (runDemo, runLiveDemo, runMaxAudit,
                // runParity*, runStateDemo): mains against live feeds that no product code calls.
                classes(
                    "com.qkt.app.MainKt",
                    "com.qkt.app.LiveDemoKt",
                    "com.qkt.app.MaxAuditKt",
                    "com.qkt.app.MaxAuditStrategy*",
                    "com.qkt.tools.*",
                )
            }
        }
        verify {
            rule("line coverage floor") {
                bound {
                    coverageUnits.set(kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                    minValue.set(floor.getProperty("line").toInt())
                }
            }
            rule("branch coverage floor") {
                bound {
                    coverageUnits.set(kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
                    minValue.set(floor.getProperty("branch").toInt())
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
