package com.qkt.marketdata.store

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DatasetSnapshotSerializationTest {
    private val fileBody =
        """{"date":"2024-01-02","path":"p","format":"jsonl","lineage":"fetch","sizeBytes":10,"sha256":"ab","tickCount":3}"""
    private val snapBody =
        """{"id":"i","qktVersion":"1","gitSha":"g","dataRoot":"/d","symbol":"XAU","vendor":"exness",
        |"from":"2024-01-01","to":"2024-02-01","files":[]}
        """.trimMargin()
    private val file = DatasetSnapshotFile("2024-01-02", "p", "jsonl", "fetch", 10L, "ab", 3)

    @Test
    fun `a file entry round trips with every field set and with defaults`() {
        roundTrips(file)
        roundTrips(
            file.copy(
                minTimestamp = 1L,
                maxTimestamp = 2L,
                maxGapMs = 5L,
                readable = false,
                bidAskTicks = 2,
                volumeTicks = 1,
            ),
        )
        decodes(fileBody, file)
        assertThat(file.maxGapMs).isZero()
        assertThat(file.readable).isTrue()
    }

    @Test
    fun `a file is empty only when readable with no ticks`() {
        assertThat(file.copy(tickCount = 0).empty).isTrue()
        assertThat(file.copy(tickCount = 0, readable = false).empty).isFalse()
        assertThat(file.empty).isFalse()
        assertThat(file.copy(readable = false).empty).isFalse()
    }

    @Test
    fun `a file entry names what is missing or mistyped`() {
        refusesWithout<DatasetSnapshotFile>(
            fileBody,
            "date",
            "path",
            "format",
            "lineage",
            "sizeBytes",
            "sha256",
            "tickCount",
        )
        refusesWrongType<DatasetSnapshotFile>(
            fileBody,
            "date",
            "sizeBytes",
            "tickCount",
            "minTimestamp",
            "maxTimestamp",
            "maxGapMs",
            "readable",
            "bidAskTicks",
            "volumeTicks",
        )
    }

    @Test
    fun `a quality policy round trips changed and default`() {
        roundTrips(DataQualityPolicy())
        roundTrips(DataQualityPolicy("lenient", 5L, true, true, true, false, false))
        decodes("{}", DataQualityPolicy())
        assertThat(DataQualityPolicy().maxGapMinutes).isEqualTo(DataQualityPolicy.DEFAULT_MAX_GAP_MINUTES)
        refusesWrongType<DataQualityPolicy>(
            "{}",
            "mode",
            "maxGapMinutes",
            "allowEmptyDays",
            "requireBidAsk",
            "requireVolume",
            "failOnCorruptDay",
            "failOnMissingDay",
        )
    }

    @Test
    fun `a snapshot round trips with every field set and with defaults`() {
        val bare =
            DatasetSnapshot(
                1,
                "i",
                "1",
                "g",
                "/d",
                "XAU",
                "exness",
                "2024-01-01",
                "2024-02-01",
                DataQualityPolicy(),
                emptyList(),
            )
        roundTrips(bare)
        roundTrips(
            bare.copy(
                schemaVersion = 2,
                qualityPolicy = DataQualityPolicy(mode = "lenient"),
                files = listOf(file),
                absentDays = listOf("2024-01-06"),
            ),
        )
        decodes(snapBody, bare)
    }

    @Test
    fun `a snapshot names what is missing or mistyped`() {
        refusesWithout<DatasetSnapshot>(
            snapBody,
            "id",
            "qktVersion",
            "gitSha",
            "dataRoot",
            "symbol",
            "vendor",
            "from",
            "to",
            "files",
        )
        refusesWrongType<DatasetSnapshot>(snapBody, "schemaVersion", "id", "qualityPolicy", "files", "absentDays")
    }
}
