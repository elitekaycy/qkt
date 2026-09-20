package com.qkt.marketdata.hub

import java.nio.file.Path

/**
 * Where the hub store lives for this process.
 *
 * A deployment mounts the hub's root read-only and names it in the environment; a local run falls
 * back to a `hub/` directory beside the data root, so a checkout works with no configuration. The
 * engine only ever reads: the hub is the sole writer of its own store, and two writers would
 * interleave sequence numbers and corrupt the ordering every consumer depends on.
 */
fun hubRoot(dataRoot: Path): Path =
    System.getenv(HubMarketSource.ROOT_ENV)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: dataRoot.resolve("hub")
