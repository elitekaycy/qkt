// What the bin/qkt and bin/qkt.bat launchers do beyond Gradle's stock scripts.
plugins {
    application
}

// The stock launcher only reads JAVA_HOME and PATH, so unpacking a self-contained archive by hand failed with no
// Java installed (#1471), and any older Java they named stopped qkt with a raw UnsupportedClassVersionError.
// Both launchers instead look for a Java new enough, starting with the bundled runtime: see scripts/launcher/.
tasks.named<CreateStartScripts>("startScripts") {
    val findJavaUnix = rootProject.file("scripts/launcher/find-java.sh")
    val findJavaWinMain = rootProject.file("scripts/launcher/find-java-main.bat")
    val findJavaWinSubs = rootProject.file("scripts/launcher/find-java-subs.bat")
    inputs.files(findJavaUnix, findJavaWinMain, findJavaWinSubs)
    doLast {
        val anchor = "# Determine the Java command to use to start the JVM.\n"
        val text = unixScript.readText()
        check(anchor in text) { "the start script changed shape: the Java lookup was not applied" }
        unixScript.writeText(text.replaceFirst(anchor, findJavaUnix.readText() + anchor))
        val winAnchor = "@rem Find java.exe"
        val win = windowsScript.readText()
        check(winAnchor in win) { "the Windows start script changed shape: the Java lookup was not applied" }
        // Subroutines go past the script's last line, so they need a `goto :eof` in front of them.
        val crlf = { s: String -> s.replace("\r\n", "\n").replace("\n", "\r\n") }
        windowsScript.writeText(
            win.replaceFirst(winAnchor, crlf(findJavaWinMain.readText()) + winAnchor).trimEnd() + "\r\n" +
                crlf(findJavaWinSubs.readText()),
        )
        applyLanguageServerJvmOptions(unixScript, windowsScript)
    }
}

// `qkt lsp` parses one small file at a time while an editor stays open for hours, so it runs in a small,
// quiet JVM: serial GC (one GC thread), a capped heap, and one C1 JIT thread. Every editor just runs
// `qkt lsp` and gets this, with nothing to configure per editor. QKT_OPTS/JAVA_OPTS still apply on top.
val languageServerJvmOptions =
    listOf("-XX:+UseSerialGC", "-Xms16m", "-Xmx256m", "-Xss512k", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=1")

fun applyLanguageServerJvmOptions(
    unixScript: File,
    windowsScript: File,
) {
    val unixDefault = "DEFAULT_JVM_OPTS='\"-XX:+UseParallelGC\"'\n"
    val unix = unixScript.readText()
    check(unixDefault in unix) { "the start script changed shape: the qkt lsp JVM options were not applied" }
    val unixLean = languageServerJvmOptions.joinToString(" ") { "\"$it\"" }
    unixScript.writeText(
        unix.replaceFirst(unixDefault, unixDefault + "[ \"\$1\" = lsp ] && DEFAULT_JVM_OPTS='$unixLean'\n"),
    )
    val winDefault = "set DEFAULT_JVM_OPTS=\"-XX:+UseParallelGC\""
    val win = windowsScript.readText()
    check(winDefault in win) { "the Windows start script changed shape: the qkt lsp JVM options were not applied" }
    val winLean = languageServerJvmOptions.joinToString(" ") { "\"$it\"" }
    windowsScript.writeText(
        win.replaceFirst(winDefault, winDefault + "\r\nif \"%~1\"==\"lsp\" set DEFAULT_JVM_OPTS=$winLean"),
    )
}
