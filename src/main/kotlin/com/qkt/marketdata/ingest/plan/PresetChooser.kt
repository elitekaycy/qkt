package com.qkt.marketdata.ingest.plan

import com.qkt.marketdata.ingest.ImportRefusal

/** What the first input looks like, for preset matching. */
data class InputShape(
    val fileName: String,
    val json: Boolean,
    val delimiter: Char?,
    /** CSV header cells, or the keys of a JSON object row; null for headerless files and array rows. */
    val header: List<String>?,
    val columns: Int,
    val firstCell: String?,
)

/**
 * Picks the built-in preset that fits a file. A preset is eligible only when every condition in its
 * [PresetMatch] holds; among eligible presets the one whose conditions say the most wins (a filename
 * pattern or an exact header says more than a column count). Two presets that fit equally well are a
 * refusal: HistData's M1 bars and NinjaTrader's bar export look identical but sit on different clocks.
 */
object PresetChooser {
    /**
     * The best preset among [presets], null when none fits; a tie refuses. [shapeFor] gives the input's
     * shape as a preset reading it would see it (a JSON preset looks at the rows under its own `rows` path),
     * or null when the preset cannot read the input at all.
     */
    fun choose(
        presets: List<ImportDescriptor> = PresetLibrary.builtIns,
        shapeFor: (ImportDescriptor) -> InputShape?,
    ): ImportDescriptor? {
        val scored =
            presets
                .mapNotNull { p -> p.match?.let { m -> shapeFor(p)?.let { score(m, it) }?.let { p to it } } }
                .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val tied = scored.filter { it.second == best.second }
        if (tied.size >
            1
        ) {
            throw ImportRefusal(
                "the file fits several presets equally: ${tied.joinToString { it.first.id }}; pass --preset",
            )
        }
        return best.first
    }

    /** How strongly [match] fits [shape], or null when one of its conditions fails. */
    internal fun score(
        match: PresetMatch,
        shape: InputShape,
    ): Int? {
        var score = 0
        if (match.json != shape.json) return null
        if (match.header.isNotEmpty()) {
            val have = shape.header?.map(::norm)?.toSet() ?: return null
            val want = match.header.map(::norm)
            if (!have.containsAll(want)) return null
            score += want.size * 2 + if (have == want.toSet()) 1 else 0
        } else if (shape.header != null && !shape.json) {
            return null
        }
        match.columns?.let { if (it != shape.columns) return null else score += 2 }
        match.firstCell?.let {
            if (shape.firstCell == null ||
                !Regex(it).matches(shape.firstCell.trim())
            ) {
                return null
            } else {
                score += 2
            }
        }
        match.filename?.let { if (!Regex(it).containsMatchIn(shape.fileName)) return null else score += 3 }
        match.delimiter?.let { if (it != shape.delimiter) return null else score += 1 }
        return score
    }

    private fun norm(s: String): String = s.trim().lowercase()
}
