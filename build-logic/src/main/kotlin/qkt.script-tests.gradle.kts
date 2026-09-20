// Shell-script tests for the audit and live-validation tooling, run as part of `check`.
// Skipped on Windows, where the scripts do not run.
plugins {
    base
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")

val auditReportBundleScriptTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the qkt report-bundle audit script against a tamper fixture."
    onlyIf { !isWindows }
    commandLine("bash", "tests/scripts/audit-qkt-report-bundle-test.sh")
    inputs.files(
        "scripts/audit_qkt_report_bundle.py",
        "tests/scripts/audit-qkt-report-bundle-test.sh",
    )
}

val prepareLiveValidationScenarioScriptTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify localhost live-validation scenario generation and static safety gates."
    onlyIf { !isWindows }
    dependsOn(tasks.named("installDist"))
    commandLine("bash", "tests/scripts/prepare-live-validation-scenario-test.sh")
    inputs.files(
        "scripts/live-validation/prepare-scenario.sh",
        "scripts/live-validation/run-readonly.sh",
        "scripts/live-validation/run-market-bracket.sh",
        "scripts/live-validation/compare-golden-replay.sh",
        "scripts/live-validation/compare-readonly-replay.sh",
        "scripts/live-validation/run-container-load.sh",
        "tests/scripts/prepare-live-validation-scenario-test.sh",
    )
}

val containerLoadScriptTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify multi-container live-load evidence gates against offline fixtures."
    onlyIf { !isWindows }
    commandLine("bash", "tests/scripts/run-container-load-test.sh")
    inputs.files(
        "scripts/live-validation/run-container-load.sh",
        "scripts/live-validation/lib/container-load-evidence.sh",
        "tests/scripts/run-container-load-test.sh",
    )
}

tasks.named("check") {
    dependsOn(auditReportBundleScriptTest, prepareLiveValidationScenarioScriptTest, containerLoadScriptTest)
}
