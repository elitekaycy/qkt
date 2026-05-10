# strategies/

Drop your `.qkt` strategy files in here. The default `docker-compose.yml` mounts this directory at `/strategies` inside the qkt daemon container; every `.qkt` file gets auto-deployed at startup via `qkt daemon --load-dir /strategies`.

For `qkt daemon` running outside Docker, use `--load-dir ./strategies` directly.

See `QUICKSTART.md` at repo root for example strategies.
