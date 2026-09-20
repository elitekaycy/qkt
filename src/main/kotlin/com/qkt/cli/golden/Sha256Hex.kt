package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest

/** SHA-256 of a file's bytes as lowercase hex. */
internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(Files.newInputStream(path), digest).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) >= 0) Unit
    }
    return digest.digest().toHex()
}

/** Lowercase hex rendering of a digest. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
