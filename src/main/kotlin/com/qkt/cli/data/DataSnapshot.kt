package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.BuildInfo
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.store.DataQualityPolicy
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DatasetSnapshots
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** `qkt data snapshot <symbol> --from --to --out` writes a hashed dataset snapshot of the cached days. */
internal fun dataSnapshot(args: Args): Int {
    val symbol =
        args.positional(1) ?: run {
            System.err.println(
                "qkt: missing symbol. usage: qkt data snapshot <symbol> --from <date> --to <date> --out <file>",
            )
            return ExitCodes.ARG_ERROR
        }
    val from = args.option("from")?.let(LocalDate::parse)
    val to = args.option("to")?.let(LocalDate::parse)
    val out = args.option("out")?.let(Path::of)
    if (from == null || to == null || out == null) {
        System.err.println("qkt: data snapshot requires --from, --to, and --out")
        return ExitCodes.ARG_ERROR
    }
    val root = DataRoot.forDataRoot(args.option("data-root"))
    val vendor = args.option("vendor") ?: "local"
    val policy = qualityPolicy(args)
    val snapshot =
        try {
            DatasetSnapshots.create(
                dataRoot = root,
                symbol = symbol,
                from = from,
                to = to,
                vendor = vendor,
                qktVersion = BuildInfo.VERSION,
                gitSha = BuildInfo.GIT_SHA,
                qualityPolicy = policy,
            )
        } catch (e: IllegalArgumentException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        } catch (e: IllegalStateException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }
    out
        .toAbsolutePath()
        .normalize()
        .parent
        ?.let(Files::createDirectories)
    Files.writeString(out, DatasetSnapshots.toJson(snapshot))
    println("qkt data snapshot: ${snapshot.id} files=${snapshot.files.size} out=$out")
    return ExitCodes.SUCCESS
}

private fun qualityPolicy(args: Args): DataQualityPolicy =
    DataQualityPolicy(
        mode = args.option("quality") ?: if (args.flag("strict")) "strict" else "research",
        maxGapMinutes = args.option("max-gap-minutes")?.toLongOrNull() ?: 30,
        allowEmptyDays = args.flag("allow-empty-days"),
        requireBidAsk = args.flag("require-bid-ask"),
        requireVolume = args.flag("require-volume"),
        failOnCorruptDay = !args.flag("allow-corrupt-days"),
        failOnMissingDay = true,
    )
