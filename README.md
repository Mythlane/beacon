# Beacon

[![CI](https://github.com/Mythlane/beacon/actions/workflows/build.yml/badge.svg)](https://github.com/Mythlane/beacon/actions/workflows/build.yml)

> OpenTelemetry observability for Hytale servers. Vendor-neutral, < 1% overhead.

**Status:** `v0.1.0-alpha` · Pre-release

Beacon is a Hytale plugin that ships metrics, traces, logs and profiles to
any OpenTelemetry-compatible backend (Grafana Cloud, Datadog, Honeycomb,
self-hosted LGTM, etc.). Drop the JAR, set two environment variables, and
you get TPS, MSPT, JVM, GC and player-count dashboards in under five minutes.

Hytale already emits internal telemetry as a proprietary JSONL format on disk.
Beacon translates the runtime to standard OTLP so server admins are not locked
into a single viewer.

---

## Quickstart (5 minutes)

> Validated procedure: see [`docs/quickstart-validation.md`](docs/quickstart-validation.md).

1. **Download** `beacon-0.1.0-alpha.jar`, `beacon-agent-0.1.0-alpha.jar`, and
   `opentelemetry-javaagent-2.27.0.jar` from the
   [Beacon CurseForge page](https://www.curseforge.com/) (alpha release).
2. **Install Beacon.** Drop `beacon-0.1.0-alpha.jar` into the `mods/` directory
   next to your `HytaleServer.jar`. The server creates this directory
   automatically the first time it boots in non-`--bare` mode.
3. **Add JVM flags** to your server launch command:
   `-javaagent:opentelemetry-javaagent-2.27.0.jar -Dotel.javaagent.extensions=beacon-agent-0.1.0-alpha.jar`
4. **Set environment** so the agent knows where to ship telemetry:
   `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` and
   `OTEL_SERVICE_NAME=hytale-server`.
5. **Bring up the local backend.** From this repo root:
   `cd examples/lgtm-stack && docker compose up -d`. This launches Tempo, Loki,
   Mimir, Pyroscope, an OpenTelemetry Collector, and Grafana with
   datasources and the Server Health dashboard auto-provisioned.
6. **Open Grafana** at <http://localhost:3000> (default login `admin` /
   `admin`) and select the Server Health dashboard. Once your server boots
   you should see TPS, MSPT, JVM heap, GC, and players-online panels populate
   within ~30 seconds.

---

## What you get (v0.1.0-alpha)

- `hytale.tps` (gauge, target 30, red threshold 27), per world.
- `hytale.mspt` (histogram, sourced from Hytale's `HistoricMetric`).
- `hytale.players.online` (counter, idempotent on `PlayerDisconnectEvent`).
- JVM auto-instrumentation (`process.runtime.jvm.*`) via the OTel Java Agent:
  heap, GC, threads, classes, file descriptors.
- Vendor-neutral OTLP export (gRPC default; HTTP/protobuf override via
  `OTEL_EXPORTER_OTLP_PROTOCOL`).
- < 1% CPU overhead measured at 20 simulated players over 10 minutes
  (release-blocker bench, 3-run median, see [`docs/perf.md`](docs/perf.md)).

---

## Architecture

Five Gradle modules keep the Hytale-SDK-dependent code isolated:

- `core`: config (env > file > default), `OpenTelemetryFactory`, export
  failure handler. No Hytale SDK dependency.
- `instrum`: domain instruments (`hytale.tps`, `hytale.mspt`, cardinality
  views).
- `binding`: the Hytale plugin (lifecycle hooks, world and player event
  subscriptions). The only module compiled against the Hytale SDK.
- `agent-ext`: OTel Java Agent extension; injects resource attributes and
  registers SPIs into the agent classloader.
- `dist`: fat-JAR assembly with Shadow shading (`io.opentelemetry`,
  `io.grpc`, `com.google.protobuf`, `io.netty`, `okhttp3`, `okio`,
  `io.perfmark` relocated under `com.mythlane.beacon.shaded.*`) plus the
  `checkShading` and `verifyServiceFiles` CI gates.

The two-JAR delivery (`beacon.jar` for the plugin, `beacon-agent.jar` for the
agent extension) is intentional: the agent extension must be loaded by the
agent's extension classloader, not the plugin classloader.

---

## Configuration

Beacon reads configuration with **env > `config.toml` > default** precedence.
Common env vars:

| Variable | Default | Notes |
|----------|---------|-------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | _required_ | e.g. `http://localhost:4317` |
| `OTEL_SERVICE_NAME` | `hytale-server` | shown as `service.name` in backends |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | or `http/protobuf` |
| `OTEL_RESOURCE_ATTRIBUTES` | _(empty)_ | extra resource attributes |

Privacy and security guidance: [`docs/security.md`](docs/security.md).

---

## Documentation

User documentation:
- [Getting started](docs/getting-started.md) — 5-minute quickstart
- [Configuration](docs/configuration.md) — environment variables,
  config.toml reference, gaps
- [Performance](docs/perf.md) — JMH benchmarks, overhead projection,
  methodology
- [Backends](docs/backends.md) — self-hosted LGTM stack setup
- [Changelog](CHANGELOG.md) — version history

Operator & contributor documentation:
- [Quickstart validation](docs/quickstart-validation.md) — release gate
  verification
- [Release procedure](docs/release-procedure.md) — how to cut a tagged
  release
- [Security](docs/security.md) — disclosure policy, SBOM, threat model

---

## License

MIT, see [`LICENSE`](LICENSE). Pre-1.0 versions may break between alphas;
strict semver from `1.0.0`.
