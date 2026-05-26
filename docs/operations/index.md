# Operations

Running qkt in production: deployment, monitoring, troubleshooting.

<div class="grid cards" markdown>

- :material-docker:{ .lg .middle } **Deploy with Docker**

    ---

    The full stack walkthrough: qkt + mt5-gateway via `docker-compose.yml`.

    [:octicons-arrow-right-24: Deploy with Docker](deploy-docker.md)

- :material-rocket-launch:{ .lg .middle } **Production deploy (qkt-prod)**

    ---

    Runbook for the managed Dokploy/Swarm host: release cadence, rollback, "what's deployed".

    [:octicons-arrow-right-24: Production deploy](deploy.md)

- :material-monitor-dashboard:{ .lg .middle } **Monitoring**

    ---

    Liveness, equity, P&L, alerts. What to scrape and what to page on.

    [:octicons-arrow-right-24: Monitoring](monitoring.md)

- :material-chart-line:{ .lg .middle } **Metrics**

    ---

    The JSON endpoints qkt exposes, plus how to write a sidecar that reformats
    them for Prometheus, Datadog, or any other monitoring stack.

    [:octicons-arrow-right-24: Metrics](metrics.md)

- :material-content-save-outline:{ .lg .middle } **State backup**

    ---

    Backing up and restoring the `qkt-state` directory — cadence and mechanism.

    [:octicons-arrow-right-24: State backup](state-backup.md)

- :material-text-box-outline:{ .lg .middle } **Logging**

    ---

    MDC keys, console + file patterns, logback overrides, JSON output.

    [:octicons-arrow-right-24: Logging](logging.md)

- :material-stethoscope:{ .lg .middle } **Troubleshooting**

    ---

    Symptom → cause → fix matrix. Use when something's wrong.

    [:octicons-arrow-right-24: Troubleshooting](troubleshooting.md)

</div>

## Not yet covered

- **Capacity planning** — strategies-per-daemon ceiling, port allocation, JVM heap sizing
- **Alerting playbook** — concrete runbooks per alert type

Pages stubbed for a future operations-depth phase. If you need one urgently, [open an issue](https://github.com/elitekaycy/qkt/issues/new).
