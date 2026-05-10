package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.broker.mt5.MT5DefaultProfiles
import java.nio.file.Path

class BrokersCommand(
    private val args: Args,
) {
    fun run(): Int =
        when (args.firstNonOption()) {
            "list" -> list()
            null -> list()
            else -> {
                System.err.println("qkt: unknown brokers subcommand")
                ExitCodes.ARG_ERROR
            }
        }

    private fun list(): Int {
        val configPath =
            args.option("config")?.let { Path.of(it) }
                ?: Path.of("./qkt.config.yaml")
        val config = Config.load(configPath)
        val profiles =
            try {
                MT5BrokerProfileLoader().load(
                    raw = config.brokers,
                    defaults = MT5DefaultProfiles.all,
                    env = System.getenv(),
                )
            } catch (e: Exception) {
                System.err.println("qkt: brokers load failed: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        if (args.flag("json")) {
            println(profiles.joinToString(",", "[", "]") { jsonProfile(it) })
            return ExitCodes.SUCCESS
        }
        println("NAME              KIND  GATEWAY                  SUFFIX  TZ  MAGIC")
        for (p in profiles) {
            println(
                "%-17s %-5s %-23s %-7s %-3s %s".format(
                    p.name,
                    "mt5",
                    p.gatewayUrl,
                    if (p.symbolPolicy.suffix.isEmpty()) "-" else p.symbolPolicy.suffix,
                    p.serverTzOffsetHours.toString(),
                    p.magic.toString(),
                ),
            )
        }
        return ExitCodes.SUCCESS
    }

    private fun jsonProfile(p: MT5BrokerProfile): String =
        """{"name":"${p.name}","kind":"mt5","gatewayUrl":"${p.gatewayUrl}",""" +
            """"symbolSuffix":"${p.symbolPolicy.suffix}","serverTzOffsetHours":${p.serverTzOffsetHours},""" +
            """"magic":${p.magic}}"""
}
