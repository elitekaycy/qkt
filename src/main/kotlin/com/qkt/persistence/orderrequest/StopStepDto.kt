package com.qkt.persistence.orderrequest

import kotlinx.serialization.Serializable

/** On-disk shape of one stepped-stop step, shared by stepped stops and stepped bracket stops. */
@Serializable
internal data class StopStepDto(
    val mfeThreshold: String,
    val profitDistance: String,
)
