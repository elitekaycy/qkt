package com.qkt.cli.options

import com.qkt.cli.CliOptionSchema

/** Option schemas for running strategies and operating the daemon and its deployments. */
internal val liveOptionSchemas: Map<String, CliOptionSchema> =
    mapOf(
        "run" to
            CliOptionSchema(
                values =
                    setOf(
                        "source",
                        "port",
                        "bind",
                        "port-file",
                        "ring-size",
                        "shutdown-timeout",
                        "config",
                    ),
                flags = setOf("allow-privileged-port", "no-observe", "flatten-on-stop"),
            ),
        "deploy" to deploySchema(),
        "resync" to deploySchema(flags = setOf("dry-run", "json")),
        "list" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
        "stop" to
            CliOptionSchema(
                values = setOf("timeout", "state-dir"),
                flags = setOf("flatten", "json"),
            ),
        "start" to CliOptionSchema(values = setOf("state-dir")),
        "halt" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
        "kill" to
            CliOptionSchema(
                values = setOf("state-dir"),
                flags = setOf("flatten", "json"),
            ),
        "reconcile" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
        "resume" to CliOptionSchema(values = setOf("state-dir"), flags = setOf("json")),
        "daemon" to
            CliOptionSchema(
                values = setOf("state-dir", "config", "control-port", "load-dir"),
                flags = setOf("json"),
            ),
        "logs" to
            CliOptionSchema(
                values = setOf("lines", "since", "state-dir"),
                flags = setOf("follow"),
                shortAliases = mapOf("-f" to "--follow"),
            ),
        "status" to
            CliOptionSchema(
                values = setOf("state-dir"),
                flags = setOf("latency", "deep"),
            ),
        "observe" to
            CliOptionSchema(
                values = setOf("strategy", "since", "windows", "state-dir", "control-port"),
            ),
    )

private fun deploySchema(flags: Set<String> = setOf("json")): CliOptionSchema =
    CliOptionSchema(
        values = setOf("as", "state-dir", "reconcile", "reason"),
        flags = flags,
        optionalValues = setOf("waive"),
    )
