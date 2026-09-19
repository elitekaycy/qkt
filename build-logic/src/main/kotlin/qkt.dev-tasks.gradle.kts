// Manual entry points for demos and data-parity tools. None run in CI.
plugins {
    java
}

fun registerMain(
    name: String,
    taskGroup: String,
    taskDescription: String,
    main: String,
) {
    tasks.register<JavaExec>(name) {
        group = taskGroup
        description = taskDescription
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(main)
    }
}

registerMain(
    "runDemo",
    "application",
    "Run the legacy mock-tick demo (predates the qkt CLI)",
    "com.qkt.app.MainKt",
)
registerMain(
    "runLiveDemo",
    "application",
    "Run the live TradingView demo",
    "com.qkt.app.LiveDemoKt",
)
registerMain(
    "runMaxAudit",
    "application",
    "End-to-end live audit across asset classes (FX, gold, crypto, stocks)",
    "com.qkt.app.MaxAuditKt",
)
registerMain(
    "runParityBarsXauusd",
    "verification",
    "Compare TradingView vs MT5 historical M5 bars for XAUUSD",
    "com.qkt.tools.parity.ParityBarsXauusdKt",
)
registerMain(
    "runParityTicksXauusd",
    "verification",
    "Compare live TradingView vs MT5 ticks for XAUUSD over a fixed window",
    "com.qkt.tools.parity.ParityTicksXauusdKt",
)
registerMain(
    "runParityDataXauusd",
    "verification",
    "Compare the backtest data source (dukascopy) vs the live broker feed (MT5) for XAUUSD",
    "com.qkt.tools.parity.ParityDukascopyMt5XauusdKt",
)
registerMain(
    "runVerifyDukascopyIndices",
    "verification",
    "Decode one dukascopy hour per mapped index and assert the price lands in band",
    "com.qkt.tools.parity.VerifyDukascopyIndexInstrumentsKt",
)
registerMain(
    "runStateDemo",
    "verification",
    "Write a sample engine-state snapshot to /tmp/qkt-demo-state and list the files",
    "com.qkt.tools.persistence.StateDemoKt",
)
