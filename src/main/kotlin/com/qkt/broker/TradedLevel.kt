package com.qkt.broker

import java.math.BigDecimal

/** True when [level] lies between [from] and [to] inclusive: a continuous move between them traded it. */
internal fun tradedThrough(
    from: BigDecimal,
    to: BigDecimal,
    level: BigDecimal,
): Boolean = from.subtract(level).signum() * to.subtract(level).signum() <= 0
