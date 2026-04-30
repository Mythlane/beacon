# Getting Started with Beacon

## What you get in 5 minutes

A local Grafana dashboard showing live TPS, MSPT (p50/p95/p99), online
players per world, JVM heap, GC pause, and threads from your Hytale dev
server. Measured Beacon overhead is well under 0.01% per server (see
[`perf.md`](perf.md)).

## Prerequisites

Beacon ships as a Hytale plugin. The Hytale server bundles its own JRE
(Eclipse Temurin 25.0.2 LTS), so **no Java installation is required**.
Beacon's bytecode target (Java 21) runs on this runtime without modification.

You need:

- **Hytale server**, version 2026.03.26-89796e57b or compatible. Beacon's
  binding layer is validated against this revision.
- **Docker + Docker Compose** for the local LGTM observability stack.
- **2 GB free RAM** for the stack containers.
- **Free local ports**: 3000 (Grafana), 4317 (OTLP gRPC), 4318 (OTLP HTTP).

The first `docker compose up` will pull ~500 MB of images. This is a
one-time cost outside the 5-minute budget; subsequent boots reuse cached
images.

## Step 1 - Download the plugin (~30s)

Download `beacon-0.1.0.jar` from the
[GitHub Releases page](https://github.com/mythlane/beacon/releases)
and drop it into your Hytale server's `mods/` directory.

```
mods/
  beacon-0.1.0.jar
```

The companion `beacon-agent-0.1.0.jar` (OpenTelemetry Java agent
extension) is **optional** for v0.1. The plugin produces all v0.1
metrics without it.

## Step 2 - Start the LGTM stack (~60s)

Clone the Beacon repo (only `examples/lgtm-stack/` is needed) and bring
up the stack. LGTM = Loki + Grafana + Tempo + Mimir, the standard
self-hosted observability bundle.

```bash
git clone https://github.com/mythlane/beacon.git
cd beacon/examples/lgtm-stack
docker compose up -d --wait
```

`--wait` blocks until all healthchecks pass. Confirm:

```bash
docker compose ps
```

All services should show status `running` or `healthy`. The stack
exposes only `127.0.0.1` ports - nothing is reachable from your network.

## Step 3 - Configure Beacon (~30s)

Beacon reads OpenTelemetry environment variables. OTLP =
OpenTelemetry Line Protocol, the wire format Beacon uses to ship
metrics. Set:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
```

On Windows PowerShell:

```powershell
$env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4317"
$env:OTEL_EXPORTER_OTLP_PROTOCOL = "grpc"
```

Alternative: place a TOML file at `mods/Mythlane.Beacon/config.toml`:

```toml
[otel.exporter.otlp]
endpoint = "http://localhost:4317"
protocol = "grpc"

[beacon]
service_name = "hytale-server-dev"
```

Precedence is **environment variables > config file > built-in defaults**.
Configuration details: [`configuration.md`](configuration.md) (à venir).

## Step 4 - Boot the server (~60s)

Start the Hytale server as you normally would. After boot, search the
logs for these two lines:

```
Beacon OpenTelemetry SDK ready: endpoint=http://localhost:4317 protocol=grpc
Beacon JVM runtime metrics enabled (memory, GC, threads, classes, CPU)
```

If you see `JVM agent not attached, falling back to in-process metrics`,
that is expected for v0.1 without the optional agent JAR. All Beacon
metrics still ship.

## Step 5 - Open the dashboard (~30s)

Open <http://localhost:3000> in a browser. Sign in with **`admin` /
`admin`** and skip the password change prompt for now.

Navigate **Dashboards -> Server Health**. After ~30 seconds of server
runtime you should see seven panels populated:

- TPS per world
- MSPT distribution (p50, p95, p99)
- JVM heap usage
- GC pause time
- Thread count
- Players online per world
- CPU utilization

![Server Health dashboard](TODO: screenshot once stack is smoke-tested)

## Troubleshooting

**"No data" in every Grafana panel.** Verify the stack is up
(`docker compose ps` shows everything `running`/`healthy`), confirm
`OTEL_EXPORTER_OTLP_ENDPOINT` is set to `http://localhost:4317` in the
shell that launched the server, and confirm Beacon booted **after** the
stack was ready (otherwise the first export attempts hit nothing).

**Plugin fails to load.** Check the server log for the loading error.
`ClassNotFoundException` usually indicates a JAR mismatch (re-download).
`IllegalAccessError` typically means another plugin is shading a
conflicting OpenTelemetry version - move other plugins aside one at a
time to find the conflict.

**Port 3000 or 4317 already in use.** Edit
`examples/lgtm-stack/docker-compose.yml` and change the host-side port,
e.g. `127.0.0.1:3001:3000` or `127.0.0.1:4327:4317`. Update
`OTEL_EXPORTER_OTLP_ENDPOINT` to match the new OTLP port.

**Logs show "Beacon active but exporting to ..." yet no data appears.**
Clock skew. Mimir rejects samples that are too far in the past or
future. Run `w32tm /query /status` (Windows) or `timedatectl` (Linux)
and re-sync NTP if your clock is more than a couple of minutes off.

**Server log shows "could not register Hytale player events".** Beacon's
binding layer is validated against Hytale `2026.03.26-89796e57b`. If you
are running an older or newer revision, the player event API may have
changed. Pin to the validated revision or open an issue with the exact
Hytale version string from your server boot log.

## Next steps

- Configuration details and tuning: [`configuration.md`](configuration.md) (à venir).
- Sending metrics to Grafana Cloud, Datadog, Honeycomb, or another OTLP
  backend: [`backends.md`](backends.md) (à venir).
- Performance and overhead numbers: [`perf.md`](perf.md).
- Architecture and contributor docs live in the repository's
  `CLAUDE.md`; that file is dev-facing, not user-facing.

---

Last validated: commit 3b94c85.
