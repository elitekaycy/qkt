# Operations

Running qkt in production: deployment, monitoring, troubleshooting.

<div class="grid cards" markdown>

- :material-docker:{ .lg .middle } **Deploy with Docker**

    ---

    The full stack walkthrough: qkt + mt5-gateway via `docker-compose.yml`.

    [:octicons-arrow-right-24: Deploy with Docker](deploy-docker.md)

- :material-text-box-outline:{ .lg .middle } **Logging**

    ---

    MDC keys, console + file patterns, logback overrides, troubleshooting.

    [:octicons-arrow-right-24: Logging](logging.md)

</div>

!!! note "More coming"
    The doc plan also targets: monitor (`/status`, `/logs`, control-plane endpoints), alert (what to page on), upgrade (version migration + rollback), troubleshoot (symptom → cause matrix), capacity (strategies-per-daemon, port allocation). Pages stubbed for v2.
