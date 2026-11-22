package com.qkt.dsl.compile

/**
 * The cross-sections the member instances of one symbol set whose members move share (runtime membership plan,
 * step 7). Each cross-member function call of the set's rules is a [site], numbered alike in every member: a member
 * [post]s its own value of the call's expression at every close of its stream, before any rule, and the call
 * [read]s the cross-section of the set's current members as of a close.
 */
interface CrossSections {
    fun post(
        site: Int,
        member: String,
        value: Value,
    )

    fun read(
        site: Int,
        atMs: Long,
    ): CrossSection
}

/**
 * One close's cross-section of a site: each running member's latest [posted] value (by alias, sticky members
 * included) and the [values] of the set's current members, the ones a cross-member function ranges over.
 */
class CrossSection(
    val posted: Map<String, Value>,
    val values: List<Value>,
)
