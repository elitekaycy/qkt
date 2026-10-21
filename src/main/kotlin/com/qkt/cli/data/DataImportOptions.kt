package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.BuildInfo
import com.qkt.marketdata.ingest.plan.BarLabel
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.PriceBasis
import com.qkt.marketdata.ingest.run.ImportRequest
import com.qkt.marketdata.ingest.run.OverlapPolicy
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * Turns `qkt data import` flags into an [ImportRequest]. Flags that describe the source become an
 * [ImportDescriptor] laid over the matched or named preset, so a flag always wins over a preset and
 * `--plan` shows the merged result. `--map` takes `field=column` pairs, comma-separated or repeated;
 * `time=` may join columns with `+` (`time=<DATE>+<TIME>`).
 */
internal object DataImportOptions {
    /** The request [args] describe; throws [IllegalArgumentException] for a malformed flag. */
    fun request(
        args: Args,
        paths: List<Path>,
        dataRoot: Path,
    ): ImportRequest {
        val symbol =
            args.option("as") ?: throw IllegalArgumentException("--as <SYMBOL> is required, e.g. --as EXNESS:XAUUSD")
        val mapped = pairs(args, "map")
        val time =
            mapped
                .firstOrNull { it.first == "time" }
                ?.second
                ?.split('+')
                ?.map { it.trim() }
                .orEmpty()
        val overrides =
            ImportDescriptor(
                kind = args.option("kind")?.let(IngestKind::parse),
                json = args.option("format")?.let { format(it) },
                delimiter = args.option("delimiter")?.let(::delimiter),
                decimal = args.option("decimal")?.let(::delimiter),
                rows = args.option("rows")?.split('.').orEmpty(),
                where = pairs(args, "where").toMap(),
                time = time,
                timeFormat = args.option("time-format"),
                clock = args.option("tz"),
                calendar =
                    args.option("calendar")?.also {
                        require(
                            com.qkt.common.TradingCalendar
                                .named(it) != null,
                        ) {
                            "--calendar must be one of ${com.qkt.common.TradingCalendar.names.joinToString()}: $it"
                        }
                    },
                timeframe = args.option("tf"),
                label = args.option("label")?.let { enumOf<BarLabel>(it, "--label", "open, close") },
                basis = args.option("price")?.let { enumOf<PriceBasis>(it, "--price", "bid, ask, mid, last") },
                fields = mapped.filter { it.first != "time" }.toMap(),
                available = args.option("available"),
                availableLag = args.option("available-lag"),
                digits =
                    args.option("digits")?.let {
                        it.toIntOrNull()
                            ?: throw IllegalArgumentException("--digits must be a whole number: $it")
                    },
            )
        return ImportRequest(
            paths = paths,
            symbol = symbol,
            dataRoot = dataRoot,
            overrides = overrides,
            preset = args.option("preset"),
            dryRun = args.flag("dry-run"),
            planOnly = args.flag("plan"),
            onOverlap = args.option("on-overlap")?.let(OverlapPolicy::parse) ?: OverlapPolicy.FILL,
            sort = args.flag("sort"),
            keepLastOnConflict = args.option("on-conflict")?.let(::conflict) ?: false,
            trustClock = args.flag("trust-clock"),
            charset = args.option("encoding")?.let(::charset),
            qktVersion = BuildInfo.VERSION,
        )
    }

    private fun conflict(text: String): Boolean =
        when (text) {
            "last" -> true
            "refuse" -> false
            else -> throw IllegalArgumentException("--on-conflict must be refuse or last: $text")
        }

    private fun charset(name: String): Charset =
        Charset.forName(if (name.equals("latin-1", true)) "ISO-8859-1" else name)

    /** Every `name=value` given to `--[flag]`, comma-separated or repeated. */
    private fun pairs(
        args: Args,
        flag: String,
    ): List<Pair<String, String>> =
        args
            .options(flag)
            .flatMap { it.split(',') }
            .filter { it.isNotBlank() }
            .map { pair(it, "--$flag") }

    private fun pair(
        text: String,
        flag: String,
    ): Pair<String, String> {
        val eq = text.indexOf('=')
        require(eq > 0 && eq < text.length - 1) { "$flag takes name=value, got '$text'" }
        return text.substring(0, eq).trim() to text.substring(eq + 1).trim()
    }

    private fun format(text: String): Boolean =
        when (text.lowercase()) {
            "json" -> true
            "csv" -> false
            else -> throw IllegalArgumentException("--format must be csv or json: $text")
        }

    private fun delimiter(text: String): Char =
        when (text) {
            "tab", "\\t" -> '\t'
            else -> text.singleOrNull() ?: throw IllegalArgumentException("expected one character (or 'tab'): '$text'")
        }

    private inline fun <reified E : Enum<E>> enumOf(
        text: String,
        flag: String,
        allowed: String,
    ): E =
        enumValues<E>().firstOrNull { it.name.equals(text, true) }
            ?: throw IllegalArgumentException("$flag must be one of $allowed: $text")
}
