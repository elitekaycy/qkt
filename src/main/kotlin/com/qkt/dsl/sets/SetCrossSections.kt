package com.qkt.dsl.sets

import com.qkt.dsl.compile.CrossSection
import com.qkt.dsl.compile.CrossSections
import com.qkt.dsl.compile.Value

/**
 * The cross-sections of one symbol set whose members move (runtime membership plan, step 7), shared by its member
 * instances. A member posts its value of each cross-member function's expression as its bar closes, before its own
 * rules; a function reads the latest values of the set's current [members] (aliases: the ranked ones, sticky
 * leftovers excluded) at the moment its rule runs. That is what a fixed set's call reads from the hub, so a moving set
 * whose members never change trades exactly like the fixed set. A member with no value yet (no bar, cold) reads
 * Undefined and is left out, as for a fixed set; a member that leaves is [forget]ten. `NEXT_STRIKE` / `PREV_STRIKE`
 * read the value of the active member [neighbourOf] names (an alias; null when there is none).
 */
internal class SetCrossSections(
    private val neighbourOf: (member: String, step: Int, atMs: Long) -> String? = { _, _, _ -> null },
    private val members: () -> Collection<String>,
) : CrossSections {
    private val sites = HashMap<Int, HashMap<String, Value>>()

    override fun post(
        site: Int,
        member: String,
        value: Value,
    ) {
        sites.getOrPut(site, ::HashMap)[member] = value
    }

    override fun read(site: Int): CrossSection {
        val posted = sites.getOrPut(site, ::HashMap)
        return CrossSection(posted, members().map { posted[it] ?: Value.Undefined })
    }

    override fun neighbour(
        site: Int,
        member: String,
        step: Int,
        atMs: Long,
    ): Value = neighbourOf(member, step, atMs)?.let { sites[site]?.get(it) } ?: Value.Undefined

    /** Drops [member]'s values: it left the set, and a rejoin posts afresh. */
    fun forget(member: String) {
        for (posted in sites.values) posted.remove(member)
    }
}
