package com.qkt.cli.soak

import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/** Copies retained evidence next to the report, owner-only; a no-op when source is already the target. */
internal fun copyArtifact(
    source: Path,
    target: Path,
) {
    if (source.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()) return
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    makeOwnerOnly(target)
}

/** Writes [content] durably to a temp sibling and moves it over [output]. */
internal fun writeAtomic(
    output: Path,
    content: String,
) {
    val temporary = output.resolveSibling(".${output.fileName}.${UUID.randomUUID()}.tmp")
    try {
        Files.writeString(
            temporary,
            content,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.DSYNC,
        )
        makeOwnerOnly(temporary)
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** SHA-256 of a file as lowercase hex. */
internal fun sha256(path: Path): String = Files.newInputStream(path).use(::sha256)

/** SHA-256 of the remaining bytes of [input] as lowercase hex. */
internal fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun makeOwnerOnly(path: Path) {
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
}
