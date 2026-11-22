package com.qkt.dsl.compile

/**
 * The cross-sections the member instances of one symbol set whose members move share (runtime membership plan,
 * step 7). Each cross-member function call of the set's rules is a [site], numbered alike in every member: a member
 * [post]s its own value of the call's expression at every close of its stream, before any rule, and the call
 * [read]s the set's current members' latest values at the moment its rule runs, as a fixed set reads the hub.
 */
interface CrossSections {
    fun post(
        site: Int,
        member: String,
        value: Value,
    )

    fun read(site: Int): CrossSection

    /**
     * The latest value at [site] of [member]'s neighbour [step] strikes away (`NEXT_STRIKE` +1, `PREV_STRIKE` -1) as
     * of [atMs]; Undefined when there is none, it is not an active member, or it has no value yet.
     */
    fun neighbour(
        site: Int,
        member: String,
        step: Int,
        atMs: Long,
    ): Value
}

/**
 * A site's cross-section at one read: each running member's latest [posted] value (by alias, sticky members
 * included) and the [values] of the set's current members, the ones a cross-member function ranges over.
 */
class CrossSection(
    val posted: Map<String, Value>,
    val values: List<Value>,
)
