# Backends

Beacon emits OTLP/gRPC by default. Any OpenTelemetry-compatible backend
works. v0.1 documents the self-hosted LGTM stack at
`examples/lgtm-stack/`.

## Quick start

```bash
cd examples/lgtm-stack
docker compose up -d
bash scripts/smoke-test.sh   # waits up to 60s for all healthy
```

Open Grafana at <http://localhost:3000> (`admin` / `admin`).
The "Server Health" dashboard is auto-provisioned from
`examples/lgtm-stack/grafana/dashboards/server-health.json`.

Configure Beacon to export to the local collector:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

Or set `otel.exporter.otlp.endpoint = "http://localhost:4317"` in your
`config.toml`.

## Components

All ports are bound to `127.0.0.1` by the compose file (not exposed on
external interfaces).

| Component      | Role             | Port (host)              |
|----------------|------------------|--------------------------|
| OTel Collector | OTLP ingest      | 4317 (gRPC), 4318 (HTTP) |
| Tempo          | Traces backend   | 3200                     |
| Loki           | Logs backend     | 3100                     |
| Mimir          | Metrics backend  | 9009                     |
| Pyroscope      | Profiles backend | 4040                     |
| Grafana        | UI               | 3000                     |

The collector also exposes its `health_check` extension on port 13133.

## Smoke test

`examples/lgtm-stack/scripts/smoke-test.sh` polls each component's health
endpoint until ready, with a 60-second deadline. Run it after
`docker compose up -d`:

```bash
bash scripts/smoke-test.sh
# OK: tempo (http://localhost:3200/ready)
# OK: loki (http://localhost:3100/ready)
# ...
# All datasources healthy.
```

Override the deadline with `TIMEOUT=120 bash scripts/smoke-test.sh`.

## Other backends

Beacon is vendor-neutral OTLP. Any OTel-compatible backend will work, but
v0.1 ships only the LGTM stack as a tested reference. Grafana Cloud,
Datadog, Honeycomb, and split self-hosted setups are deferred to v0.2 —
see the [v0.2 backlog issues](https://github.com/Mythlane/beacon/issues?q=is%3Aissue+label%3Av0.2).
