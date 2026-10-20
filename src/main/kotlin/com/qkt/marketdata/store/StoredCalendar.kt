package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import java.nio.file.Files

/**
 * The calendar [symbol]'s bars were imported on (`--calendar`, kept as `calendar` in a timeframe's manifest), or null
 * when no import stated one and the symbol takes qkt's default for its name.
 */
fun LocalBarStore.calendarOf(
    broker: String,
    symbol: String,
): TradingCalendar? {
    val dir = root.resolve("bars").resolve(broker).resolve(symbol)
    if (!Files.isDirectory(dir)) return null
    val names =
        Files.list(dir).use { entries ->
            entries.toList().filter { Files.isRegularFile(it.resolve("manifest.json")) }.mapNotNull {
                readManifest(broker, symbol, it.fileName.toString()).calendar
            }
        }
    return names.firstOrNull()?.let(TradingCalendar::named)
}
