package com.qkt.dsl.compile

import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext

interface DslCompiledStrategy : Strategy {
    val declaredStreams: Map<String, HubKey>
    val retentionByKey: Map<HubKey, Int>

    fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    )
}
