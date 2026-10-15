package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.plan.ImportDescriptor
import java.nio.charset.Charset
import java.nio.file.Path

/** What to do with stored data an import would overwrite. */
enum class OverlapPolicy {
    /** Keep each stored day unless the incoming one covers more hours of it (the default). */
    FILL,

    /** Refuse, listing the days. */
    REFUSE,

    /** Keep the stored days, import the rest. */
    SKIP,

    /** Move the stored days to `.import-backup/` and write the import's. */
    REPLACE,
    ;

    companion object {
        /** The policy `--on-overlap` names. */
        fun parse(text: String): OverlapPolicy =
            entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("--on-overlap must be fill, refuse, skip or replace: '$text'")
    }
}

/**
 * One `qkt data import` invocation: the files, the symbol they become, the user's own description of the
 * source ([overrides], from flags), an explicit [preset] (built-in id or YAML file), and how to behave.
 */
data class ImportRequest(
    val paths: List<Path>,
    val symbol: String,
    val dataRoot: Path,
    val overrides: ImportDescriptor = ImportDescriptor(),
    val preset: String? = null,
    val dryRun: Boolean = false,
    val planOnly: Boolean = false,
    val onOverlap: OverlapPolicy = OverlapPolicy.FILL,
    /** Sort a file whose rows are out of time order instead of refusing it. */
    val sort: Boolean = false,
    /** Where the source holds two different values for one time, keep the later file's instead of refusing. */
    val keepLastOnConflict: Boolean = false,
    /** Accept a stated clock even when the weekly opens contradict it. */
    val trustClock: Boolean = false,
    val charset: Charset? = null,
    /** The qkt version recorded in provenance. */
    val qktVersion: String = "unknown",
)
