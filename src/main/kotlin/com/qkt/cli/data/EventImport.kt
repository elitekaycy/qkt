package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.instrument.EventCatalog
import com.qkt.instrument.EventCatalogStore
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive

/**
 * `qkt data import <file>... --as <VENUE:FAMILY> --kind events [--map field=column] [--delimiter C] [--dry-run]
 * [--json]`
 *
 * Reads an event family's contracts from any delimited file with a header, one contract a row ([EventRows]), and
 * merges them into `<data root>/contracts/<VENUE>/<FAMILY>.events.json` ([EventCatalogStore]), the family's event
 * catalog. Sources are descriptions: `--map` names the columns (qkt's own file needs none), no venue is recognised by
 * name. A contract already catalogued is replaced by its row, keeping an outcome the row leaves empty, so a later
 * import of settled results fills them in; an outcome that contradicts a recorded one is refused. Every row is read
 * and checked before anything is written.
 */
internal object EventImport {
    const val KIND = "events"
    private val FAMILY = Regex("[A-Z0-9_]+:[A-Za-z0-9_]+")

    fun run(
        args: Args,
        paths: List<Path>,
        dataRoot: Path,
    ): Int {
        val root = args.option("as")
        if (root == null || !FAMILY.matches(root)) {
            System.err.println("qkt: --as <VENUE:FAMILY> is required for events, e.g. --as KALSHI:KXINXU")
            return ExitCodes.ARG_ERROR
        }
        val store = EventCatalogStore(dataRoot)
        val (rows, merged) =
            try {
                val reader = EventRows(columns(args), args.option("delimiter")?.singleOrNull() ?: ',')
                val rows = paths.flatMap(reader::read)
                rows to (store.read(root) ?: EventCatalog(root, emptyList())).merge(rows)
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: ${e.message}\n  Nothing was written.")
                return ExitCodes.USER_ERROR
            }
        val dryRun = args.flag("dry-run")
        if (!dryRun) store.write(merged)
        val resolved = merged.contracts.count { it.outcome != null }
        val path = store.path(root)
        if (args.flag("json")) {
            println(
                """{"family":"$root","read":${rows.size},"stored":${merged.contracts.size},"resolved":$resolved,""" +
                    """"path":${JsonPrimitive(path.toString())},"dryRun":$dryRun}""",
            )
        } else {
            val verb = if (dryRun) "would merge into" else "merged into"
            println(
                "$root: ${rows.size} contract(s) read; $verb $path, which holds ${merged.contracts.size} " +
                    "($resolved resolved)",
            )
        }
        return ExitCodes.SUCCESS
    }

    /** The column each field is read from: qkt's own names unless `--map` says otherwise. */
    private fun columns(args: Args): Map<String, String> {
        val mapped =
            args.options("map").flatMap { it.split(',') }.filter { it.isNotBlank() }.associate {
                val (field, column) =
                    it.split('=', limit = 2).map(String::trim).takeIf { p -> p.size == 2 }
                        ?: throw IllegalArgumentException("--map takes field=column: $it")
                require(field in EventRows.FIELDS) {
                    "--map field '$field' is not one of ${EventRows.FIELDS.joinToString()}"
                }
                field to column
            }
        return EventRows.FIELDS.associateWith { mapped[it] ?: it }
    }
}
