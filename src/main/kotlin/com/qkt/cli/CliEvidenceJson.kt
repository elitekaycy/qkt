package com.qkt.cli

import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceJson

internal object CliEvidenceJson {
    fun pinnedDataset(dataset: DatasetEvidence): String? {
        if (dataset.mutableStore) return null
        val id = dataset.id ?: return null
        val hash = dataset.hash ?: return null
        return buildString {
            append("{\"id\":").append(EvidenceJson.jsonString(id))
            append(",\"hash\":").append(EvidenceJson.jsonString(hash))
            append(",\"qualityPolicy\":").append(EvidenceJson.jsonString(dataset.qualityPolicy ?: "unknown"))
            append(",\"mutableStore\":false")
            append("}")
        }
    }
}
