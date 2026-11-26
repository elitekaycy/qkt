package com.qkt.dsl.compile

/**
 * A strategy that mints order ids from more generators than its own [DslCompiledStrategy.orderIds] (a moving symbol
 * set's members, each under its own prefix): the last sequence each one issued, by prefix, persists at every submit,
 * and a restart resumes every one past it, so no id is ever sent twice, finished orders' ids included.
 */
interface OrderIdMarks {
    /** The last sequence each generator issued, by prefix; generators that issued nothing are left out. */
    fun orderIdMarks(): Map<String, Long>

    /** Resumes every generator past its mark in [marks] (by prefix), from a restart's persisted marks. */
    fun resumeOrderIdMarks(marks: Map<String, Long>)
}
