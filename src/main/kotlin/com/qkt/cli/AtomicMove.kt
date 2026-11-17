package com.qkt.cli

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Moves [source] to [target] atomically; on a file system that cannot, with a plain move. [options] apply to both
 * attempts. [atomicMove] is the atomic attempt, a seam for the file systems this machine does not have.
 */
internal fun moveAtomically(
    source: Path,
    target: Path,
    vararg options: StandardCopyOption,
    atomicMove: (
        Path,
        Path,
        Array<out CopyOption>,
    ) -> Unit = { from, to, copyOptions -> Files.move(from, to, *copyOptions) },
) {
    try {
        atomicMove(source, target, arrayOf(StandardCopyOption.ATOMIC_MOVE, *options))
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, *options)
    }
}
