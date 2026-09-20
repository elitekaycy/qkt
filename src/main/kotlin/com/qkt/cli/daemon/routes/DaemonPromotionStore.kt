package com.qkt.cli.daemon.routes

import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionStore
import com.qkt.cli.UserDirs
import com.qkt.cli.daemon.StateDir

/**
 * The promotion registry the daemon reads and writes: the configured registry dir, else
 * the daemon state root, else the user state home.
 */
internal fun promotionStore(
    stateDir: StateDir?,
    gates: PromotionGateConfig,
): PromotionStore =
    PromotionStore(
        gates.registryDir
            ?: stateDir?.stateRoot?.resolve("promotion")
            ?: UserDirs().stateHome().resolve("state").resolve("promotion"),
    )
