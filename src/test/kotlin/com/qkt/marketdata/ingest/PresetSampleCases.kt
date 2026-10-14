package com.qkt.marketdata.ingest

import org.junit.jupiter.params.provider.Arguments

/** One sample per built-in preset: file, `--as` symbol, the preset it must match, its first stored row, extra mappings. */
object PresetSampleCases {
    private fun s(
        file: String,
        symbol: String,
        preset: String,
        row: String,
        fields: Map<String, String> = emptyMap(),
    ) = Arguments.of(file, symbol, preset, row, fields)

    private const val Z = "0.00000000"

    val all: List<Arguments> =
        listOf(
            s(
                "mt5-bars.csv",
                "EXNESS:XAUUSD",
                "mt5-bars",
                "1709935080000,2178.78000000,2179.03000000,2178.69000000,2179.00000000,198.00000000",
            ),
            s("mt5-ticks.csv", "XAUUSD", "mt5-ticks", "1710104401112,XAUUSD,,,2180.51200000,2180.67200000,,"),
            s(
                "mt4-csv.csv",
                "BROKER:EURUSD",
                "mt4-csv",
                "1709935080000,1.09380000,1.09391000,1.09377000,1.09388000,41.00000000",
            ),
            s(
                "DAT_MT_EURUSD_M1_2017.csv",
                "HISTDATA:EURUSD",
                "histdata-mt",
                "1483340400000,1.05155000,1.05197000,1.05155000,1.05190000,$Z",
            ),
            s(
                "DAT_ASCII_EURUSD_M1_2017.csv",
                "HISTDATA:EURUSD",
                "histdata-m1",
                "1483340400000,1.05155000,1.05197000,1.05155000,1.05190000,$Z",
            ),
            s(
                "DAT_ASCII_EURUSD_T_201912.csv",
                "EURUSD",
                "histdata-ticks",
                "1575237603296,EURUSD,,,1.10229000,1.10247000,,",
            ),
            s(
                "EURUSD_Candlestick_1_M_BID_01.01.2024-31.01.2024.csv",
                "DUKASCOPY:EURUSD",
                "dukascopy-gmt-bars",
                "1704153600000,1.10377000,1.10392000,1.10370000,1.10383000,1248.17000000",
            ),
            s(
                "dukascopy-local-bars.csv",
                "DUKASCOPY:EURUSD",
                "dukascopy-local-bars",
                "1710108000000,1.09401000,1.09412000,1.09395000,1.09410000,55.10000000",
            ),
            s(
                "dukascopy-gmt-ticks.csv",
                "EURUSD",
                "dukascopy-gmt-ticks",
                "1456128000065,EURUSD,,,1.10560000,1.10563000,1.50000000,0.75000000",
            ),
            s(
                "dukascopy-node-bars.csv",
                "DUKASCOPY:EURUSD",
                "dukascopy-node-bars",
                "1612137600000,1.21225000,1.21363000,1.20560000,1.20676000,165569.81870000",
            ),
            s(
                "dukascopy-node-ticks.csv",
                "US500",
                "dukascopy-node-ticks",
                "1517209500230,US500,,,2871.97000000,2872.63000000,0.00198000,0.00204000",
            ),
            s(
                "tradingview-unix.csv",
                "OANDA:EURUSD",
                "tradingview-unix",
                "1595311200000,1.14432000,1.14631000,1.14308000,1.14613000,25413.00000000",
            ),
            s(
                "tradingview-iso.csv",
                "XETR:SAP",
                "tradingview-iso",
                "1704182400000,17.05000000,17.40000000,16.80000000,16.85000000,108.00000000",
            ),
            s(
                "binance-klines.csv",
                "BINANCE_UM:BTCUSDT",
                "binance-klines",
                "1590969600000,9446.49000000,9447.64000000,9432.04000000,9438.00000000,297.11100000",
            ),
            s(
                "binance-klines-us.csv",
                "BINANCE:BTCUSDT",
                "binance-klines",
                "1748736000000,104591.88000000,104647.11000000,104591.88000000,104647.11000000,10.71688000",
            ),
            s(
                "binance-klines-header.csv",
                "BINANCE_UM:BTCUSDT",
                "binance-klines-header",
                "1748736000000,104544.60000000,104599.60000000,104544.60000000,104599.50000000,72.71100000",
            ),
            s(
                "binance-klines.json",
                "BINANCE:ETHBTC",
                "binance-klines-json",
                "1499040000000,0.01634790,0.80000000,0.01575800,0.01577100,148976.11427815",
            ),
            s("binance-funding.csv", "BINANCE_UM:BTCUSDT", "binance-funding", "1704067200000,0.00037409,"),
            s("binance-funding-api.json", "BINANCE_UM:BTCUSDT", "binance-funding-api", "1568102400000,0.00010000,"),
            s("binance-metrics.csv", "BINANCE_UM:BTCUSDT", "binance-metrics", "1598919000000,39080.23100000"),
            s("bybit-trades.csv", "BTCUSDT", "bybit-trades", "1791849600169,BTCUSDT,81807.60000000,0.01200000,,,,"),
            s(
                "bybit-spot-trades.csv",
                "BTCUSDT",
                "bybit-spot-trades",
                "1791849601483,BTCUSDT,81852.10000000,0.02970000,,,,",
            ),
            s(
                "okx-candles.csv",
                "OKX:BTCUSDT",
                "okx-candles",
                "1727712000000,63558.80000000,63583.90000000,63539.90000000,63576.10000000,6.76110263",
            ),
            s("okx-trades.csv", "BTCUSDT", "okx-trades", "1727712000750,BTCUSDT,63558.80000000,0.00157157,,,,"),
            s(
                "kraken-ohlcvt.csv",
                "KRAKEN:XBTUSD",
                "kraken-ohlcvt",
                "1381095240000,122.00000000,122.00000000,122.00000000,122.00000000,0.10000000",
            ),
            s("truefx-ticks.csv", "EURUSD", "truefx-ticks", "1462147200959,EURUSD,,,1.14601000,1.14605000,,"),
            s(
                "yfinance-daily.csv",
                "YAHOO:SPX",
                "yfinance-daily",
                "1199232000000,1467.96997100,1471.77002000,1442.06994600,1447.16003400,3452650000.00000000",
            ),
            s(
                "yfinance-intraday.csv",
                "YAHOO:AAPL",
                "yfinance-intraday",
                "1704205800000,187.15000000,188.44000000,186.95000000,188.11000000,8124110.00000000",
            ),
            s(
                "yfinance-multi.csv",
                "YAHOO:AAPL",
                "yfinance-multi",
                "1704153600000,185.78000000,186.40000000,182.72000000,184.29000000,82488700.00000000",
            ),
            s(
                "alphavantage-intraday.csv",
                "AV:IBM",
                "alphavantage-intraday",
                "1791935100000,230.61000000,230.74000000,230.60000000,230.70000000,512.00000000",
            ),
            s(
                "alphavantage-daily.csv",
                "AV:IBM",
                "alphavantage-daily",
                "1791590400000,227.00000000,229.50000000,226.80000000,229.10000000,38123456.00000000",
            ),
            s(
                "polygon-aggs-flat.csv",
                "POLYGON:AAPL",
                "polygon-aggs-flat",
                "1744792500000,195.01000000,195.20000000,194.98000000,195.10000000,1200.00000000",
            ),
            s(
                "polygon-aggs.json",
                "POLYGON:AAPL",
                "polygon-aggs-json",
                "1704205800000,187.15000000,188.44000000,186.95000000,188.11000000,81240.00000000",
            ),
            s(
                "oanda-candles.json",
                "OANDA:EURUSD",
                "oanda-candles",
                "1476732900000,1.09950000,1.09958000,1.09940000,1.09948000,132.00000000",
            ),
            s(
                "ccxt-ohlcv.json",
                "BITSTAMP:BTCUSD",
                "ccxt-ohlcv",
                "1504541580000,4235.40000000,4240.60000000,4230.00000000,4230.70000000,37.72941911",
            ),
            s(
                "qkt-cot.csv",
                "CFTC:GC_MANAGED_LONG",
                "qkt-cot",
                "1497225600000,229476.00000000,229476.00000000,229476.00000000,229476.00000000,$Z",
                mapOf("value" to "MANAGED_LONG"),
            ),
        )
}
