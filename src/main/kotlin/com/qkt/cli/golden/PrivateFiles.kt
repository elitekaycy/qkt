package com.qkt.cli.golden

import com.qkt.common.OwnerOnly
import java.nio.file.Files
import java.nio.file.Path

/** Restricts a directory to its owner (POSIX mode or Windows ACL). */
internal fun makePrivateDirectory(path: Path) {
    OwnerOnly.directory(path)
}

/** Restricts a file to its owner (POSIX mode or Windows ACL). */
internal fun makePrivateFile(path: Path) {
    OwnerOnly.file(path)
}

/** Deletes a directory tree, children before parents. */
internal fun deleteTree(path: Path) {
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
