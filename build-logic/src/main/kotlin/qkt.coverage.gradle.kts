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
