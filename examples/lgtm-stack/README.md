# Beacon — LGTM example stack

Self-contained Grafana + Tempo + Loki + Mimir + Pyroscope stack for verifying
Beacon end-to-end on a local machine. No SaaS account required.

## Quickstart

```bash
cd examples/lgtm-stack
docker compose up -d --wait
bash scripts/smoke-test.sh
```

Then point Beacon at the OTel Collector:

```
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

Open Grafana at <http://localhost:3000> (admin / admin) and navigate to the
**Beacon → Server Health** dashboard. The 7 panels (TPS, MSPT p50/p95/p99, JVM
memory, GC, threads, players online, CPU) will populate once Beacon starts
exporting.

## Endpoints

| Service          | URL                              |
|------------------|----------------------------------|
| Grafana          | <http://localhost:3000>          |
| OTel Collector   | localhost:4317 (gRPC) / 4318 (HTTP) |
| Tempo (HTTP)     | <http://localhost:3200>          |
| Loki             | <http://localhost:3100>          |
| Mimir            | <http://localhost:9009>          |
| Pyroscope        | <http://localhost:4040>          |

All ports are bound to `127.0.0.1` (localhost-only). To expose them to your LAN,
edit `docker-compose.yml`.

## Security

This stack is **local-development only**:

- Grafana ships with `admin/admin` — change before exposing publicly.
- Backends run with auth disabled (single-tenant, local).
- For production, use a managed LGTM (Grafana Cloud) or harden each component
  individually.

## Teardown

```bash
docker compose down -v
```

The `-v` flag also removes anonymous volumes. Container data is ephemeral —
restart loses traces/metrics/logs.

## Reference

For the full Beacon quickstart (server install + plugin drop), see the project
root `README.md`.
