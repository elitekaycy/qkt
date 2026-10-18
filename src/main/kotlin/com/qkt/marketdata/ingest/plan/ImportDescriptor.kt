package com.qkt.marketdata.ingest.plan

/** What a file holds, and which store it lands in. */
enum class IngestKind(
    /** The name `--kind` and presets use. */
    val spec: String,
    /** The roles a mapping may fill for this kind. */
    val roles: Set<String>,
) {
    TICKS("ticks", setOf("bid", "ask", "price", "volume", "bidVolume", "askVolume")),
    BARS("bars", setOf("open", "high", "low", "close", "volume", "spread")),
    FUNDING("funding", setOf("rate", "price")),
    OPEN_INTEREST("open-interest", setOf("openInterest")),
    SERIES("series", setOf("value")),
    ;

    companion object {
        /** The kind [text] names. */
        fun parse(text: String): IngestKind =
            entries.firstOrNull { it.spec == text.trim() }
                ?: throw IllegalArgumentException("unknown kind '$text' (expected ${entries.joinToString { it.spec }})")
    }
}

/** Which price a source's bar or single-price tick quotes. qkt treats a bar's prices as mid. */
enum class PriceBasis { BID, ASK, MID, LAST }

/** Whether a bar's timestamp is its open (qkt's convention) or its close. */
enum class BarLabel { OPEN, CLOSE }

/**
 * How to tell that a preset fits a file. Every condition that is set must hold; the preset whose
 * conditions say the most about the file wins.
 */
data class PresetMatch(
    /** Header names that must all be present (case and surrounding space ignored). */
    val header: List<String> = emptyList(),
    /** Exact number of columns, for headerless files and JSON array rows. */
    val columns: Int? = null,
    /** A regex the first data row's first cell must match. */
    val firstCell: String? = null,
    /** A regex the input's file name must match. */
    val filename: String? = null,
    /** The delimiter the file must use. */
    val delimiter: Char? = null,
    /** True when the input must be JSON. */
    val json: Boolean = false,
)

/**
 * Everything the import engine needs to know about a source that the file does not say about itself:
 * which columns hold what, how times are written and on which clock, which price a bar quotes, what
 * volume means. A built-in preset is one of these in YAML; flags and `--preset my.yaml` override it field
 * by field; `--plan` prints the resolved result, which re-imports identically.
 */
data class ImportDescriptor(
    val id: String = "custom",
    val description: String? = null,
    val kind: IngestKind? = null,
    val json: Boolean? = null,
    val delimiter: Char? = null,
    val decimal: Char? = null,
    /** JSON only: the keys leading to the rows inside the document. */
    val rows: List<String> = emptyList(),
    /** Rows are kept only when each named cell equals its value (Oanda's `complete: true`). */
    val where: Map<String, String> = emptyMap(),
    /** The cells holding the timestamp, joined with a space (`<DATE>` and `<TIME>`). */
    val time: List<String> = emptyList(),
    val timeFormat: String? = null,
    val clock: String? = null,
    val timeframe: String? = null,
    val label: BarLabel? = null,
    val basis: PriceBasis? = null,
    /** Role (see [IngestKind.roles]) to mapping expression. */
    val fields: Map<String, String> = emptyMap(),
    /** Series only: the cell or expression giving when a value became available. */
    val available: String? = null,
    val availableLag: String? = null,
    val availableFormat: String? = null,
    val digits: Int? = null,
    /** What the stored volume means, for provenance (e.g. `tick count`). */
    val volume: String? = null,
    /** Ticks only: a blank bid or ask repeats the previous one (MetaTrader tick exports). */
    val carryForward: Boolean? = null,
    /**
     * Prices are float32 values widened to a double (yfinance: `340.79998779296875` is the float32 nearest 340.8):
     * each is stored as the shortest decimal naming that float32. A price that is not exactly a float32 is kept.
     */
    val float32: Boolean? = null,
    val match: PresetMatch? = null,
) {
    /** This descriptor with every field [other] sets replacing this one's; [fields] merge by role. */
    fun overlaidBy(other: ImportDescriptor): ImportDescriptor =
        ImportDescriptor(
            id = if (other.id != "custom") other.id else id,
            description = other.description ?: description,
            kind = other.kind ?: kind,
            json = other.json ?: json,
            delimiter = other.delimiter ?: delimiter,
            decimal = other.decimal ?: decimal,
            rows = other.rows.ifEmpty { rows },
            where = where + other.where,
            time = other.time.ifEmpty { time },
            timeFormat = other.timeFormat ?: timeFormat,
            clock = other.clock ?: clock,
            timeframe = other.timeframe ?: timeframe,
            label = other.label ?: label,
            basis = other.basis ?: basis,
            fields = fields + other.fields,
            available = other.available ?: available,
            availableLag = other.availableLag ?: availableLag,
            availableFormat = other.availableFormat ?: availableFormat,
            digits = other.digits ?: digits,
            volume = other.volume ?: volume,
            carryForward = other.carryForward ?: carryForward,
            float32 = other.float32 ?: float32,
            match = match,
        )
}
