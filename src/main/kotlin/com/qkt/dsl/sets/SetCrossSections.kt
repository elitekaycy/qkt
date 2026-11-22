package com.qkt.dsl.sets

import com.qkt.dsl.compile.CrossSection
import com.qkt.dsl.compile.CrossSections
import com.qkt.dsl.compile.Value

/**
 * The cross-sections of one symbol set whose members move (runtime membership plan, step 7), shared by its member
 * instances. A member posts its value of each cross-member function's expression as its bar closes; a function
 * reads the values of the set's current [members] (aliases: the ranked ones, sticky leftovers excluded), taken once
 * per close at the first read, so every member reading at that close sees the same cross-section, built from values
 * already posted and never from a later bar. A member with no value yet (no bar, cold) reads Undefined and is left
 * out, as for a fixed set; a member that leaves is [forget]ten.
 */
internal class SetCrossSections(
    private val members: () -> Collection<String>,
) : CrossSections {
    private val sites = HashMap<Int, Site>()

    override fun post(
        site: Int,
        member: String,
        value: Value,
    ) {
        sites.getOrPut(site, ::Site).posted[member] = value
    }

    override fun read(
        site: Int,
        atMs: Long,
    ): CrossSection {
        val s = sites.getOrPut(site, ::Site)
        if (s.takenAtMs != atMs) {
            s.taken = CrossSection(HashMap(s.posted), members().map { s.posted[it] ?: Value.Undefined })
            s.takenAtMs = atMs
        }
        return s.taken
    }

    /** Drops [member]'s values: it left the set, and a rejoin posts afresh. */
    fun forget(member: String) {
        for (s in sites.values) s.posted.remove(member)
    }

    private class Site {
        val posted = HashMap<String, Value>()
        var takenAtMs = Long.MIN_VALUE
        var taken = CrossSection(emptyMap(), emptyList())
    }
}
