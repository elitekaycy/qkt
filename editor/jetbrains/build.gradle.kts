plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.qkt"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    intellijPlatform {
        // -PlocalIde=<dir> compiles against an installed IDE instead of downloading one.
        val localIde = providers.gradleProperty("localIde").orNull
        if (localIde != null) local(localIde) else intellijIdea("2025.3.1")
        bundledPlugin("org.jetbrains.plugins.textmate")
        plugin("com.redhat.devtools.lsp4ij", "0.21.0")
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

// The grammar and language settings are the VS Code extension's, so highlighting cannot drift from it.
tasks.prepareSandbox {
    from(rootProject.file("../vscode")) {
        into("qkt-jetbrains/bundle")
        include("package.json", "language-configuration.json", "syntaxes/**")
    }
}
