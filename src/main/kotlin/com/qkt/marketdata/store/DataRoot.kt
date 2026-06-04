package com.qkt.marketdata.store

import java.nio.file.Path

object DataRoot {
    const val ENV: String = "QKT_DATA_HOME"

    fun resolve(): Path = resolveExplicit(System.getenv(ENV))

    fun resolveExplicit(env: String?): Path =
        if (env != null) {
            Path.of(env)
        } else {
            Path.of(System.getProperty("user.home"), ".qkt", "data")
        }

    /**
     * Store root that honors an explicit `--data-root` flag over the [ENV] var and the default.
     * e.g. `forDataRoot("./data")` -> `./data`; `forDataRoot(null)` -> [resolve].
     */
    fun forDataRoot(dataRoot: String?): Path = dataRoot?.let { Path.of(it) } ?: resolve()
}
