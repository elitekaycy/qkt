plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("1.5.0")
    filter {
        exclude { it.file.path.contains("/build/generated-sources/") }
    }
}
