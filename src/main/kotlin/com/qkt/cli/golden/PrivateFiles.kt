package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Restricts a directory to its owner where the filesystem supports POSIX permissions. */
internal fun makePrivateDirectory(path: Path) {
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
}

/** Restricts a file to owner read/write where the filesystem supports POSIX permissions. */
internal fun makePrivateFile(path: Path) {
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
}

/** Deletes a directory tree, children before parents. */
internal fun deleteTree(path: Path) {
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
