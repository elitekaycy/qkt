package com.qkt.dsl.compile

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.TifAst
import com.qkt.execution.TimeInForce

object TifTranslator {
    fun translate(tif: TifAst?): TimeInForce =
        when (tif) {
            null, Gtc -> TimeInForce.GTC
            Ioc -> TimeInForce.IOC
            Fok -> TimeInForce.FOK
            Day -> TimeInForce.DAY
            is Gtd ->
                error(
                    "TIF GTD is deferred — engine TimeInForce enum has no GTD variant; " +
                        "revisit alongside engine deadline-bearing order surface",
                )
        }
}
