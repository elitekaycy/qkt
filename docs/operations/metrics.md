# Metrics

qkt exposes operational data over HTTP, in JSON, on the daemon's control port.
**The JSON endpoints are the canonical metrics surface.** Whatever monitoring stack
you run — Prometheus, Datadog, CloudWatch, a Discord webhook, a Grafana dashboard,
a one-off CSV dump — your job is to point a consumer at those endpoints and
reformat. qkt doesn't ship dashboards or have opinions about your storage.

A small built-in convenience consumer (`/metrics`, Prometheus text format) is
included for the common case. It can be turned off.

## The JSON surface — what qkt produces

All endpoints are `GET`, all return JSON, all live on the daemon's control port
(`--control-port`, default ephemeral, written to `<state-dir>/control.port`).

| Endpoint | Returns |
|---|---|
| `/health` | `{"status":"ok","strategies":N,"uptimeMs":M}` — liveness + counts |
| `/list` | One row per deployed strategy/portfolio with state, trade count, port, uptime |
| `/status` | Array of per-strategy snapshots: positions, equity, realized/unrealized, last trade, pending stack layers, broker-per-stream routing |
| `/status/<name>` | Single strategy's snapshot |
| `/latency` | Per-`(strategy, stage)` percentile snapshots — `count`, `p50Nanos`, `p95Nanos`, `p99Nanos`, `maxNanos`. Requires `QKT_LATENCY_TRACKING=1` to populate; otherwise `{"enabled":false,...}` |
| `/logs/<name>` | Recent log lines for a strategy |

Stable contract — these are the shapes consumers can rely on.

## Consuming the data — write a sidecar

Whatever format you need, write (or copy) a tiny consumer that polls the JSON
and emits whatever your stack wants. Three sketches:

### Prometheus (Python)

```python
import time, requests
from http.server import BaseHTTPRequestHandler, HTTPServer

QKT = "http://qkt:47000"  # daemon control port

class Exporter(BaseHTTPRequestHandler):
    def do_GET(self):
        health = requests.get(f"{QKT}/health").json()
        strategies = requests.get(f"{QKT}/status").json()
        latency = requests.get(f"{QKT}/latency").json()

        lines = [
            "# TYPE qkt_daemon_uptime_seconds gauge",
            f'qkt_daemon_uptime_seconds {health["uptimeMs"] // 1000}',
            "# TYPE qkt_strategies_count gauge",
            f'qkt_strategies_count {health["strategies"]}',
        ]
        for s in strategies:
            lines += [
                "# TYPE qkt_strategy_realized_total counter",
                f'qkt_strategy_realized_total{{strategy="{s["name"]}"}} {s.get("realized", 0)}',
            ]
        if latency.get("enabled"):
            lines.append("# TYPE qkt_pipeline_latency_p99_nanoseconds gauge")
            for strat, by_stage in latency["strategies"].items():
                for stage, snap in by_stage.items():
                    lines.append(
                        f'qkt_pipeline_latency_p99_nanoseconds{{strategy="{strat}",'
                        f'stage="{stage}"}} {snap["p99Nanos"]}'
                    )

        body = "\n".join(lines).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.end_headers()
        self.wfile.write(body)

HTTPServer(("0.0.0.0", 9100), Exporter).serve_forever()
```

Point Prometheus at `:9100`. Done.

### Datadog (Bash)

```bash
#!/usr/bin/env bash
while true; do
  health=$(curl -s http://qkt:47000/health)
  uptime_ms=$(echo "$health" | jq .uptimeMs)
  echo "monotonic_count:qkt.uptime_seconds:$((uptime_ms / 1000))" \
    | nc -w1 -u localhost 8125
  sleep 15
done
```

### Slack alerts (Node)

```javascript
const fetch = require("node-fetch");
setInterval(async () => {
  const latency = await (await fetch("http://qkt:47000/latency")).json();
  for (const [strat, byStage] of Object.entries(latency.strategies || {})) {
    for (const [stage, snap] of Object.entries(byStage)) {
      if (snap.p99Nanos > 500_000_000) {  // 500ms
        await fetch(process.env.SLACK_WEBHOOK, {
          method: "POST",
          body: JSON.stringify({
            text: `qkt ${strat}/${stage} p99=${snap.p99Nanos / 1e6}ms`
          }),
        });
      }
    }
  }
}, 15_000);
```

The pattern is the same in any language: poll JSON, transform, ship.

## The built-in `/metrics` endpoint

For Prometheus users who want zero-config monitoring, qkt ships a built-in
Prometheus exporter as a convenience. Hit it the same way as any other endpoint:

```bash
curl http://qkt:47000/metrics
```

It exposes the same data as the JSON endpoints, formatted as Prometheus text
exposition. **It is not the canonical contract — it's one consumer.** When the
JSON surface evolves, `/metrics` will follow as a courtesy, but consumers who
want to pin behavior should poll the JSON endpoints directly.

### Turning it off

To skip the `/metrics` route entirely (e.g. when running your own exporter sidecar
and you don't want the unused endpoint), set the env var on the daemon:

```yaml
# docker-compose.yml
services:
  qkt:
    environment:
      QKT_METRICS_PROMETHEUS: "false"
```

Accepted "off" values: `false`, `0`, `off`, `no`. Anything else (or unset) keeps
the endpoint on.

When disabled, `GET /metrics` returns `404 not found` like any other unknown route.

## What's NOT in the built-in `/metrics`

Three things, deliberately. If you need them, write a sidecar that consumes the
corresponding JSON.

- **Per-strategy latency percentiles.** Available in `/latency` JSON. Format-stable.
- **Broker-specific counters.** Available per-strategy in `/status` → `streamBrokers`.
- **Anything you'd want to alert on with specific thresholds.** That's a decision
  your monitoring stack should make, not qkt.

## Why this shape

qkt has one job: run strategies. The data it produces should be format-neutral
so different operators can plug different consumers in without qkt becoming a
half-finished Prometheus/Datadog/OTLP/etc. exporter. The JSON endpoints stay
forever; the Prometheus built-in is just a batteries-included convenience for
the most common path.
