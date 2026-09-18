// Packaging: the editor bundle in the tarball, the jlink runtime, and the self-contained
// linux and Windows distributions the installers download.
plugins {
    application
}

val toolExt = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""

distributions {
    named("main") {
        contents {
            // Bundle the editor integrations under share/editor/ in the tarball.
            // `qkt editor install <target>` reads them from $QKT_HOME/share/editor/.
            // .vsix is included when present (built by release.yml before distTar).
            from(rootProject.file("editor")) {
                into("share/editor")
                exclude("**/node_modules/**")
            }
        }
    }
}

// A jlink-minimized JRE carrying only the modules qkt loads (mirrors the Dockerfile).
// jdk.crypto.ec (TLS) and jdk.unsupported (sun.misc.Unsafe, used by okio/kotlin) are
// loaded reflectively, so jdeps misses them and they are named explicitly.
val jlinkRuntimeDir = layout.buildDirectory.dir("jlink-runtime")
val jlinkRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a minimal JRE with only the modules qkt needs."
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    outputs.dir(jlinkRuntimeDir)
    doFirst {
        val out = jlinkRuntimeDir.get().asFile
        delete(out) // jlink refuses to write into an existing directory
        commandLine(
            "${launcher.get().metadata.installationPath.asFile.absolutePath}/bin/jlink$toolExt",
            "--add-modules",
            "java.base,java.logging,java.naming,java.xml,jdk.httpserver,jdk.crypto.ec,jdk.unsupported",
            "--strip-debug",
            "--no-man-pages",
            "--no-header-files",
            "--compress=zip-6",
            "--output",
            out.absolutePath,
        )
    }
}

// Self-contained linux-x64 distribution: the app plus the bundled runtime, so `qkt`
// runs with no system Java. Powers the one-shot installer (#57). The launcher reads
// JAVA_HOME, which the installer points at the bundled runtime/ directory.
tasks.register<Tar>("selfContainedDist") {
    group = "distribution"
    description = "App plus a bundled jlink runtime (no system Java required)."
    dependsOn("installDist", jlinkRuntime)
    compression = Compression.GZIP
    archiveFileName.set("qkt-${project.version}-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("qkt") {
        from(layout.buildDirectory.dir("install/qkt"))
        from(jlinkRuntimeDir) { into("runtime") }
    }
}

// Windows self-contained app-image: a real qkt.exe plus the bundled jlink runtime,
// so qkt runs on Windows with no system Java. The Windows twin of selfContainedDist.
// jpackage targets the host OS, so a real Windows image is produced only when this
// runs on a Windows JDK (CI: windows-latest). On Linux it yields a host-OS image,
// which is enough to validate the task wiring locally.
val jpackageImageDir = layout.buildDirectory.dir("jpackage")

val windowsAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a Windows app-image (qkt.exe + bundled runtime) via jpackage."
    dependsOn("installDist", jlinkRuntime)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val libDir = layout.buildDirectory.dir("install/qkt/lib")
    val runtimeDir = jlinkRuntimeDir
    val outDir = jpackageImageDir
    val ver = project.version.toString()
    outputs.dir(outDir)
    doFirst {
        val out = outDir.get().asFile

        delete(out) // jpackage refuses to write into an existing app-image dir
        out.mkdirs()
        val jpackage =
            "${launcher.get().metadata.installationPath.asFile.absolutePath}/bin/jpackage$toolExt"
        val args =
            mutableListOf(
                jpackage,
                "--type",
                "app-image",
                "--name",
                "qkt",
                "--input",
                libDir.get().asFile.absolutePath,
                "--main-jar",
                "qkt-$ver.jar",
                "--main-class",
                "com.qkt.cli.MainKt",
                "--runtime-image",
                runtimeDir.get().asFile.absolutePath,
                "--dest",
                out.absolutePath,
            )

        // qkt is a console CLI, but jpackage app-image launchers default to the GUI
        // subsystem, so the JVM exit code never reaches the shell (qkt.exe --version
        // prints fine yet reports failure). Force a console launcher. --win-console is
        // Windows-only, so guard on the host tool suffix.
        if (toolExt == ".exe") args.add("--win-console")
        commandLine(args)
    }
}

// Zip the Windows app-image with the editor bundle into the release asset. The
// installer extracts this to %LOCALAPPDATA%\Programs\qkt and points QKT_HOME there,
// so `qkt editor install` finds share/editor (jpackage puts jars under app/, not
// lib/, so EditorPaths' lib-dir fallback does not fire — QKT_HOME is the locator).
tasks.register<Zip>("windowsSelfContainedDist") {
    group = "distribution"
    description = "Zip the Windows app-image (qkt.exe + runtime + editor files)."
    dependsOn(windowsAppImage)
    archiveFileName.set("qkt-${project.version}-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("qkt") {
        from(jpackageImageDir.map { it.dir("qkt") })
        from(rootProject.file("editor")) {
            into("share/editor")
            exclude("**/node_modules/**")
        }
    }
}

tasks.register("dockerBuild") {
    group = "distribution"
    description = "Build the qkt Docker image at qkt:local"
    doLast {
        exec { commandLine("docker", "build", "-t", "qkt:local", ".") }
    }
}
