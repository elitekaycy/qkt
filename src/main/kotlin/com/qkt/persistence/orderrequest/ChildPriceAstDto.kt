package com.qkt.persistence.orderrequest

import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import kotlinx.serialization.Serializable

/**
 * On-disk shape of a bracket child-price expression (`at`, `by`, `pct`, `rr`, armed trail), kept
 * so a restored pre-fill bracket re-resolves its exits against the real fill price.
 */
@Serializable
internal data class ChildPriceAstDto(
    val type: String,
    val first: FillAnchorExprDto,
    val second: FillAnchorExprDto? = null,
) {
    fun toDomain(): ChildPriceAst =
        when (type) {
            "At" ->
                ChildAt(first.toDomain())
            "By" ->
                ChildBy(first.toDomain())
            "Pct" ->
                ChildPct(first.toDomain())
            "Rr" ->
                ChildRr(first.toDomain())
            "ArmedTrail" ->
                ChildArmedTrail(
                    trailDistance = first.toDomain(),
                    mfeThreshold =
                        requireNotNull(second) {
                            "ArmedTrail child-price DTO missing mfeThreshold"
                        }.toDomain(),
                )
            else -> error("Unknown bracket child-price type in persisted state: $type")
        }

    companion object {
        fun fromDomain(value: ChildPriceAst): ChildPriceAstDto =
            when (value) {
                is ChildAt ->
                    ChildPriceAstDto("At", FillAnchorExprDto.fromDomain(value.price))
                is ChildBy ->
                    ChildPriceAstDto("By", FillAnchorExprDto.fromDomain(value.distance))
                is ChildPct ->
                    ChildPriceAstDto("Pct", FillAnchorExprDto.fromDomain(value.percent))
                is ChildRr ->
                    ChildPriceAstDto("Rr", FillAnchorExprDto.fromDomain(value.multiplier))
                is ChildArmedTrail ->
                    ChildPriceAstDto(
                        "ArmedTrail",
                        FillAnchorExprDto.fromDomain(value.trailDistance),
                        FillAnchorExprDto.fromDomain(value.mfeThreshold),
                    )
            }
    }
}
