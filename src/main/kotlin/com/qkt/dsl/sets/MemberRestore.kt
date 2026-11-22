package com.qkt.dsl.sets

import com.qkt.persistence.StatePersistor

/**
 * What a restarted session hands a moving set's members as they join: the order ids it restored ([usedIds]), every
 * generator's persisted mark ([marks], by prefix), and where member state persists ([persistor]). Empty in a run that
 * starts fresh, as every backtest does.
 */
internal class MemberRestore {
    var usedIds: Collection<String> = emptyList()
    var marks: Map<String, Long> = emptyMap()
    var persistor: StatePersistor? = null
}
