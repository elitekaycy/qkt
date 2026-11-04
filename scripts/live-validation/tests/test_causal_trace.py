"""The causal-chain trace (lib/causal_trace.py), offline: live orders from a golden bundle against a replay report.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import json, os, sys, tempfile, unittest, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import causal_trace as ct  # noqa: E402

T0 = 1_793_758_080_000
TRADES_HEADER = ("timestamp,strategy,symbol,side,positionEffect,orderType,quantity,price,realized,netAccountRealized,"
                 "grossAccountRealized,brokerOrderId,legId")


def engine(kind, seq, ts, **fields):
    return {"v": 1, "ts": ts, "seq": seq, "eventType": f"com.qkt.events.{kind}", **fields}


def request(oid, side="BUY", created=T0 + 392, symbol="EXNESS:BTCUSD"):
    return {"orderId": oid, "orderType": "Market", "symbol": symbol, "side": side, "qty": 0.01,
            "strategyId": "s", "timeInForce": "GTC", "createdTs": created,
            "legIntent": {"kind": "Open", "legId": oid, "role": "INDEPENDENT"}, "closesLegId": None,
            "partialClose": False}


def live_bundle(path, price="77050.69", costs="0", transport=True, symbol="EXNESS:BTCUSD", financing=False):
    """One market buy decided at T0+392 on the T0 bar close, sent at T0+430, filled at [price] at T0+1063."""
    records = [
        engine("TickEvent", 1, T0 + 385, symbol=symbol,
               tick={"timestampMs": T0 + 311, "price": "77051.39", "bid": "77048.19", "ask": "77054.59"}),
        engine("TickEvent", 2, T0 + 1036, symbol=symbol,
               tick={"timestampMs": T0 + 931, "price": "77047.49", "bid": "77044.29", "ask": "77050.69"}),
        engine("RuleDecisionEvent", 3, T0 + 389, strategyId="s", decisionId="d1", ruleId="r#0",
               candle={"endTimeMs": T0}),
        engine("DecisionOrderLinkedEvent", 4, T0 + 393, orderId="o-1", decisionId="d1"),
        engine("OrderEvent", 5, T0 + 403, orderId="o-1", order=request("o-1", symbol=symbol)),
        engine("BrokerEvent.OrderFilled", 6, T0 + 1063, orderId="o-1", symbol=symbol,
               fill={"side": "BUY", "price": price, "quantity": "0.01", "brokerOrderId": "343", "partial": False}),
        engine("FillAccountedEvent", 7, T0 + 1063, orderId="o-1", symbol=symbol,
               accountedFill={"totalCostsAccount": costs, "netAccountRealized": "0"}),
    ]
    if financing:  # a perpetual's funding the venue realized at the close, booked as a cost under no order
        records.append(engine("FillAccountedEvent", 8, T0 + 2000, orderId="cost:funding tx-1", symbol=symbol,
                              accountedFill={"fillSliceId": "cost:funding tx-1", "kind": "FINANCING",
                                             "totalCostsAccount": "0", "netAccountRealized": "-0.00001379"}))
    exchange = {"v": 1, "ts": T0 + 1040, "method": "POST", "path": "/order", "engineOrderId": "o-1", "responseCode": 200,
                "responseBody": json.dumps({"result": {"price": float(price), "order": 343,
                                                       "request": [1, 9, 0, "BTCUSDm", 0.01, 77054.59]}})}
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("engine/audit-2026-11-04.jsonl", "".join(json.dumps(r) + "\n" for r in records))
        if transport:
            zf.writestr("gateway/exness/transport-2026-11-04.jsonl", json.dumps(exchange) + "\n")


def replay_report(directory, price="77054.59", created=T0 + 311, costs="0", extra=None):
    os.makedirs(directory, exist_ok=True)
    orders = [{"decision": "approved", "request": request("o-1", created=created)}]
    rows = [f"{created},s,EXNESS:BTCUSD,BUY,OPEN_LONG,Market,0.01,{price},0,{-float(costs)},0,o-1,o-1"]
    if extra:
        orders.append({"decision": "approved", "request": request(extra, created=created)})
        rows.append(f"{created},s,EXNESS:BTCUSD,BUY,OPEN_LONG,Market,0.01,{price},0,0,0,{extra},{extra}")
    with open(os.path.join(directory, "orders.jsonl"), "w") as file:
        file.write("".join(json.dumps(o) + "\n" for o in orders))
    with open(os.path.join(directory, "trades.csv"), "w") as file:
        file.write(TRADES_HEADER + "\n" + "\n".join(rows) + "\n")


class CausalTraceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.golden = os.path.join(self.tmp, "golden.zip")
        self.report = os.path.join(self.tmp, "report")

    def test_identical_chains_have_no_mismatch(self):
        live_bundle(self.golden, price="77054.59")
        replay_report(self.report)

        result = ct.trace(self.golden, self.report)

        self.assertEqual(result["parityMismatches"], 0, result["mismatches"])
        self.assertEqual(result["explained"], [])
        steps = [s["step"] for s in result["orders"][0]["steps"]]
        self.assertEqual(steps, ["decision", "risk", "transport", "fill", "accounted"])

    def test_a_venue_filling_at_a_later_captured_quote_is_explained_by_latency(self):
        live_bundle(self.golden, price="77050.69")
        replay_report(self.report, price="77054.59")

        result = ct.trace(self.golden, self.report)

        self.assertEqual(result["parityMismatches"], 0, result["mismatches"])
        fill = next(s for s in result["orders"][0]["steps"] if s["step"] == "fill")
        self.assertEqual(fill["explained"]["cause"], "venue-latency")
        self.assertEqual(fill["explained"]["requestedPrice"], "77054.59")
        self.assertEqual(fill["explained"]["venueQuoteAtMs"], T0 + 931)

    def test_a_price_no_captured_quote_explains_is_a_mismatch(self):
        live_bundle(self.golden, price="77040.00")
        replay_report(self.report, price="77054.59")

        result = ct.trace(self.golden, self.report)

        self.assertEqual([m["step"] for m in result["mismatches"]], ["fill"])

    def test_a_decision_one_bar_early_is_a_mismatch(self):
        live_bundle(self.golden, price="77054.59")
        replay_report(self.report, created=T0 + 311 - 60_000)

        result = ct.trace(self.golden, self.report)

        self.assertIn({"orderId": "o-1", "step": "decision", "index": None}, result["mismatches"])

    def test_costs_the_venue_did_not_charge_are_a_mismatch_even_at_the_same_price(self):
        live_bundle(self.golden, price="77054.59", costs="0")
        replay_report(self.report, price="77054.59", costs="0.0385")

        result = ct.trace(self.golden, self.report)

        self.assertEqual({m["step"] for m in result["mismatches"]}, {"fill", "accounted"})

    def test_funding_the_venue_booked_is_listed_not_compared_as_an_order(self):
        live_bundle(self.golden, price="77054.59", financing=True)
        replay_report(self.report)

        result = ct.trace(self.golden, self.report)

        self.assertEqual(result["parityMismatches"], 0, result["mismatches"])
        self.assertEqual([o["orderId"] for o in result["orders"]], ["o-1"])
        self.assertEqual(result["financing"], [{"id": "cost:funding tx-1", "symbol": "EXNESS:BTCUSD",
                                                 "ts": T0 + 2000, "netRealized": "-0.00001379"}])

    def test_an_order_only_the_replay_placed_is_a_mismatch(self):
        live_bundle(self.golden, price="77054.59")
        replay_report(self.report, extra="o-2")

        result = ct.trace(self.golden, self.report)

        extra = next(o for o in result["orders"] if o["orderId"] == "o-2")
        self.assertTrue(extra["replayOnly"])
        self.assertTrue(any(m["orderId"] == "o-2" for m in result["mismatches"]))

    def test_a_gateway_leg_reads_its_transport_from_venues(self):
        live_bundle(self.golden, price="77054.59", transport=False, symbol="PAPER:BTC_USDC_PERPETUAL")
        with zipfile.ZipFile(self.golden, "a") as zf:
            zf.writestr("venues/PAPER/transport-2026-11-04.jsonl", json.dumps(
                {"v": 1, "ts": T0 + 440, "method": "POST", "path": "/v1/orders", "engineOrderId": "o-1",
                 "responseCode": 201}) + "\n")

        live = ct.live_chains(self.golden)[0]

        self.assertEqual([e["path"] for e in live["o-1"]["transport"]], ["/v1/orders"])

    def test_a_closing_realized_gap_is_explained_only_when_the_explained_prices_add_up_to_it(self):
        def order(oid, side, live_price, replay_price, live_realized, replay_realized, ts):
            fill = {"step": "fill", "index": 0, "match": False, "explained": {"cause": "venue-latency"},
                    "live": {"ts": ts, "side": side, "price": live_price, "quantity": "0.01", "contractSize": "1",
                             "costs": "0", "reducedExposure": side == "SELL"},
                    "replay": {"price": replay_price, "costs": "0"}}
            accounted = {"step": "accounted", "index": 0, "match": live_realized == replay_realized,
                         "live": {"netRealized": live_realized}, "replay": {"netRealized": replay_realized}}
            return {"orderId": oid, "symbol": "EXNESS:BTCUSD", "steps": [fill, accounted]}

        right = [order("o-1", "BUY", "77050.69", "77054.59", "0", "0", 1),
                 order("o-2", "SELL", "77052.19", "77054.82", "0.015", "0.0023", 2)]
        wrong = [order("o-1", "BUY", "77050.69", "77054.59", "0", "0", 1),
                 order("o-2", "SELL", "77052.19", "77054.82", "0.016", "0.0023", 2)]

        ct.explain_realized(right)
        ct.explain_realized(wrong)

        self.assertEqual(right[1]["steps"][1]["explained"]["cause"], "follows-explained-fill")
        self.assertNotIn("explained", wrong[1]["steps"][1])

    def test_two_venues_closing_on_one_bar_may_swap_ids_and_still_pair_by_intent(self):
        def chain(symbol, side, created, bar=None):
            c = {"request": dict(request("x", side=side, created=created, symbol=symbol), closesLegId="leg-" + symbol),
                 "fills": [], "accounted": [], "transport": []}
            if bar is not None:
                c["decision"] = {"barCloseMs": bar}
            return c
        live = {"c-0": chain("PAPER:P", "BUY", T0 + 190, bar=T0), "c-1": chain("EXNESS:E", "SELL", T0 + 730, bar=T0)}
        replay = {"c-0": chain("EXNESS:E", "SELL", T0 + 59), "c-1": chain("PAPER:P", "BUY", T0 + 63)}

        self.assertEqual(ct.pair_orders(live, replay, ct.TOLERANCE_MS), {"c-0": "c-1", "c-1": "c-0"})
        self.assertTrue(ct._same_bar_peers(live, "c-0", "c-1"))

    def test_the_cli_writes_the_trace_and_exits_non_zero_on_a_mismatch(self):
        live_bundle(self.golden, price="77040.00")
        replay_report(self.report)
        out = os.path.join(self.tmp, "trace.json")

        code = ct.main([self.golden, self.report, out])

        self.assertEqual(code, 1)
        with open(out) as file:
            self.assertEqual(json.load(file)["schema"], "qkt-causal-trace-v1")


if __name__ == "__main__":
    unittest.main()
