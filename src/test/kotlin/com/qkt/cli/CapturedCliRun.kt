package com.qkt.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream

/** Exit code plus everything a CLI action printed to stdout and stderr. */
data class CapturedCliRun(
    val code: Int,
    val out: String,
    val err: String,
)

/** Runs [block] with stdout and stderr captured (and stdin fed from [stdin]), restoring all three after. */
fun captureCliRun(
    stdin: String? = null,
    block: () -> Int,
): CapturedCliRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val savedOut = System.out
    val savedErr = System.err
    val savedIn: InputStream = System.`in`
    System.setOut(PrintStream(out, true))
    System.setErr(PrintStream(err, true))
    if (stdin != null) System.setIn(ByteArrayInputStream(stdin.toByteArray()))
    val code =
        try {
            block()
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
            System.setIn(savedIn)
        }
    return CapturedCliRun(code, out.toString(), err.toString())
}

/** Runs [block] with the `user.home` system property pointed at [home], restoring it after. */
fun <T> withUserHome(
    home: java.nio.file.Path,
    block: () -> T,
): T {
    val saved = System.getProperty("user.home")
    System.setProperty("user.home", home.toString())
    return try {
        block()
    } finally {
        System.setProperty("user.home", saved)
    }
}
