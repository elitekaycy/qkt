package com.qkt.cli.observe

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Announces the observability port on stdout + writes it atomically to a port file.
 *
 * The daemon uses this to discover each strategy's port without parsing strategy logs.
 */
object PortPrinter {
    /** Prints `QKT_PORT=<port>` to [out] and (optionally) writes the same value to [portFile]. */
    fun announce(
        host: String,
        port: Int,
        portFile: Path? = null,
        out: PrintStream = System.out,
    ) {
        out.println("[INFO] observability: http://$host:$port")
        out.println("QKT_PORT=$port")
        out.flush()
        portFile?.let { writeAtomic(it, port.toString()) }
    }

    private fun writeAtomic(
        target: Path,
        content: String,
    ) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
