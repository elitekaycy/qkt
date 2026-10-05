package com.qkt.derivatives.futures

import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Gold lists every calendar month but trades only G/J/M/Q/V/Z (qkt#1341). The chain must skip the
 * listed-but-dead months by comparing each contract with its chronological neighbours, so the filter
 * holds across eras whose absolute volume differs a hundredfold.
 */
class ActiveDeliveryMonthsTest {
    private val policy = RollPolicy(7, LocalTime.MIDNIGHT, PriceAdjustment.PANAMA)
    private val months = "FGHJKMNQUVXZ"
    private val active = "GJMQVZ"

    /** One contract per calendar month of [years], with the liquidity figures a vendor archive gives. */
    private fun catalogJson(
        years: IntRange,
        volumeOf: (year: Int, code: Char) -> Long,
        openInterestOf: (year: Int, code: Char) -> Long? = { _, _ -> null },
    ): String {
        val rows =
            years.flatMap { year ->
                months.mapIndexed { m, code ->
                    val expiry =
                        LocalDate
                            .of(year, m + 1, 26)
                            .atTime(21, 0)
                            .toInstant(ZoneOffset.UTC)
                            .toEpochMilli()
                    val oi = openInterestOf(year, code)?.let { ""","peakOpenInterest":"$it"""" } ?: ""
                    """{"symbol":"GC$code${year % 100}","expiryMs":$expiry,""" +
                        """"lifetimeVolume":"${volumeOf(year, code)}"$oi}"""
                }
            }
        return """{"root":"CME:GC","contracts":[${rows.joinToString(",")}]}"""
    }

    private fun schedule(
        dir: Path,
        json: String,
    ): RollSchedule {
        Files.createDirectories(dir.resolve("contracts/CME"))
        Files.writeString(dir.resolve("contracts/CME/GC.json"), json)
        val catalog = requireNotNull(ContractCatalogStore(dir).read("CME:GC"))
        return RollSchedule(catalog.contracts, policy)
    }

    @Test
    fun `listed but untraded delivery months never join the chain, in any era`(
        @TempDir dir: Path,
    ) {
        // Volume grows 512x over the decade, so a late dead month outtrades an early live one: a fixed
        // threshold that drops the early dead months keeps the late ones.
        val json =
            catalogJson(2000..2009, { year, code -> (if (code in active) 1_000_000L else 5_000L) shl (year - 2000) })
        val chained = schedule(dir, json).contracts.map { it.symbol }
        assertThat(chained).hasSize(60)
        assertThat(chained.map { it[2] }.toSet()).containsExactlyInAnyOrderElementsOf(active.toList())
    }

    @Test
    fun `early contracts far below the all-time median are kept when normal for their era`(
        @TempDir dir: Path,
    ) {
        val json = catalogJson(2000..2009, { year, _ -> 10_000L * (1L shl (year - 2000)) })
        assertThat(schedule(dir, json).contracts).hasSize(120)
    }

    @Test
    fun `normal open interest keeps a contract whose volume was under-reported`(
        @TempDir dir: Path,
    ) {
        // Kaggle's 2008 FX volume is ~50x under-reported while open interest is intact.
        val json =
            catalogJson(
                2006..2010,
                { year, code -> if (year == 2008 && code in "JKM") 10_000L else 1_000_000L },
                { _, _ -> 200_000L },
            )
        assertThat(schedule(dir, json).contracts).hasSize(60)
    }

    @Test
    fun `a catalog without liquidity figures chains every contract`(
        @TempDir dir: Path,
    ) {
        val json = catalogJson(2000..2001, { _, _ -> 1L }).replace(Regex(""","lifetimeVolume":"\d+""""), "")
        assertThat(schedule(dir, json).contracts).hasSize(24)
    }
}
