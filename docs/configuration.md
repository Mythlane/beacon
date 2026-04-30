# Beacon Configuration

This document is the exhaustive v0.1 reference for configuring Beacon.
It also calls out gaps explicitly: settings that the cahier des charges
plans but that are not yet implemented in v0.1.

## Sources of configuration

Beacon reads its configuration from three sources, in this precedence
order (highest wins):

1. **Environment variables** — read at server boot.
2. **TOML file** — `mods/Mythlane_Beacon/config.toml` next to the plugin JAR.
3. **Built-in defaults** — compiled into `BeaconConfig`.

Concrete example: if the file sets
`otel.exporter.otlp.endpoint = "http://otel-collector:4317"` and the
shell exports `OTEL_EXPORTER_OTLP_ENDPOINT=http://staging:4317`, the
shell value wins. Empty environment variables (`""`) are treated as
unset and do not override the file.

`ConfigBootstrap` (in the `binding` module) wraps the loader with a
catch-all: if the TOML file is malformed, missing, oversized
(>1 MiB), or any unexpected error occurs during load, Beacon logs a
warning and falls back to the built-in defaults. The plugin never
prevents the Hytale server from booting because of a config issue.

## Quick reference

| Setting | TOML key | Env var | Default | Source of truth |
|---|---|---|---|---|
| OTLP endpoint | `otel.exporter.otlp.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Beacon |
| OTLP protocol | `otel.exporter.otlp.protocol` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | Beacon |
| Service name | `otel.service.name` | `OTEL_SERVICE_NAME` | `hytale-server` | Beacon |
| Export queue capacity | `beacon.queue.max_size` | *(none)* | `16384` | Beacon |
| OTLP headers | *(none)* | `OTEL_EXPORTER_OTLP_HEADERS` | *(empty)* | OTel autoconfigure |
| Resource attributes | *(none)* | `OTEL_RESOURCE_ATTRIBUTES` | *(empty)* | OTel autoconfigure |
| Plugin kill switch | `beacon.enabled` | `BEACON_ENABLED` | n/a | **Planned, v0.2** |
| Metric export interval | `beacon.metrics.interval` | `BEACON_METRICS_INTERVAL` | n/a | **Planned, v0.2** |

## Field-by-field reference

Beacon TOML keys follow the OpenTelemetry naming convention
`otel.exporter.<protocol>.<setting>`. The `.otlp.` segment is part of
the standard OTel SDK property layout, not a Beacon decision; the env
var equivalents follow the same pattern (`OTEL_EXPORTER_OTLP_*`).

### `otel.exporter.otlp.endpoint`

- **Type**: string (URL with scheme + host + port).
- **Default**: `http://localhost:4317`.
- **Description**: where Beacon ships metrics. Use `http://` for
  unencrypted local dev, `https://` for production backends.

TOML:

```toml
[otel.exporter.otlp]
endpoint = "https://otlp.eu-west-2.grafana.net:443"
```

Env:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.eu-west-2.grafana.net:443
```

### `otel.exporter.otlp.protocol`

- **Type**: string, one of `grpc`, `http/protobuf`.
- **Default**: `grpc`.
- **Description**: wire protocol Beacon uses to talk to the endpoint.
  `grpc` (port 4317) is more efficient. `http/protobuf` (port 4318) is
  easier behind reverse proxies that don't terminate gRPC.

TOML:

```toml
[otel.exporter.otlp]
protocol = "http/protobuf"
```

Env:

```bash
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

If the endpoint port and protocol disagree (e.g. `4317` with
`http/protobuf`), the connection fails at first export and the export
failure handler trips the circuit breaker. See `perf.md` for the
breaker behavior.

### `otel.service.name`

- **Type**: string.
- **Default**: `hytale-server`.
- **Description**: identifies your server in the backend. Use a unique
  value per server when you run multiple, otherwise dashboards will
  aggregate them. Maps to the OpenTelemetry `service.name` resource
  attribute, see [OTel semantic conventions for service](https://opentelemetry.io/docs/specs/semconv/resource/#service).

TOML:

```toml
[otel.service]
name = "hytale-eu-west-pvp-01"
```

Env:

```bash
export OTEL_SERVICE_NAME=hytale-eu-west-pvp-01
```

### `beacon.queue.max_size`

- **Type**: positive integer.
- **Default**: `16384`.
- **Env var**: not exposed; file-only.
- **Description**: capacity of the in-memory export queue managed by
  `ExportFailureHandler`. When the backend is slow or unreachable,
  Beacon queues exports up to this limit, then drops with a counter.

TOML:

```toml
[beacon.queue]
max_size = 32768
```

**Caveat**: `ConfigBootstrap` does not echo `queue.max_size` in the
startup log, so you cannot confirm your override took effect by reading
logs alone. Verify via the metric `beacon.export.queue.capacity` once
exported (or by `beacon.export.dropped_total` ramping under known load).

### Honored via OTel SDK autoconfigure

These settings have no Beacon TOML key. Beacon's
`OpenTelemetryFactory` builds the SDK with
`AutoConfiguredOpenTelemetrySdk`, which honors the standard OTel
environment variables.

#### `OTEL_EXPORTER_OTLP_HEADERS`

- **Type**: comma-separated `key=value` pairs.
- **Default**: empty.
- **Description**: HTTP/gRPC headers attached to every export request.
  Used for backend authentication.

```bash
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic dXNlcjpwYXNz"
```

For Grafana Cloud, the value is `Authorization=Basic <base64(instance_id:api_token)>`.
Refer to your backend's documentation for the exact format.

#### `OTEL_RESOURCE_ATTRIBUTES`

- **Type**: comma-separated `key=value` pairs.
- **Default**: empty.
- **Description**: extra resource attributes attached to every emitted
  metric. Use this to add `service.namespace` and
  `deployment.environment`, both standard OTel semantic conventions.

```bash
export OTEL_RESOURCE_ATTRIBUTES="service.namespace=mythlane,deployment.environment=prod,service.instance.id=eu-west-pvp-01"
```

### Planned, not yet implemented

#### `beacon.enabled` / `BEACON_ENABLED` — **v0.2**

- **Status**: not implemented in v0.1. The plugin is always active
  when its JAR is present.
- **Workaround for v0.1**: remove `beacon-0.1.0.jar` from `mods/` and
  restart the server to disable Beacon. There is no runtime kill switch.

#### `beacon.metrics.interval` / `BEACON_METRICS_INTERVAL` — **v0.2**

- **Status**: not implemented. The export interval is hardcoded to 30 s
  (`PeriodicMetricReader` default in v0.1).
- **Workaround for v0.1**: none. Backends that need a different cadence
  must scrape from a collector that reaggregates.

## Configuration file format

The TOML file lives at `mods/Mythlane_Beacon/config.toml`, alongside
the plugin JAR. The file is **not auto-created** in v0.1 — Beacon runs
on defaults if the file is missing, no warning is logged. Create the
file by hand the first time you need to override a default.

The TOML 1.0 specification is at <https://toml.io/en/v1.0.0>. Beacon
parses with `org.tomlj.Toml` and rejects files larger than 1 MiB to
prevent boot-time stalls on accidental writes.

A complete v0.1 file with every supported key looks like this:

```toml
[otel.exporter.otlp]
endpoint = "http://localhost:4317"
protocol = "grpc"

[otel.service]
name = "hytale-server"

[beacon.queue]
max_size = 16384
```

Unknown keys are ignored silently — TOML parsing succeeds, Beacon just
doesn't read them. This means typos like `otel.exporter.otpl.endpoint`
fall back to defaults without warning. Double-check key names against
this document.

## Validation and fallback

Beacon never fails the server boot because of a config issue.
`ConfigBootstrap.load()` catches every `Throwable` from the loader and
returns `BeaconConfig.defaults()` with a WARN log. Triggers include:

- File present but unreadable (permission denied, locked).
- File larger than 1 MiB.
- TOML parse errors (syntax, duplicate keys, invalid types).
- Invalid values (e.g. `beacon.queue.max_size = 0` or negative).

The startup log line that confirms a successful load is:

```
INFO  Beacon config loaded: endpoint=... service=... protocol=...
```

If you instead see:

```
WARN  Beacon config load failed; using defaults
```

the config file did not load. The accompanying stack trace identifies
the cause.

### Limitations of v0.1 startup logging

The startup log only echoes three of the supported settings (endpoint,
service name, protocol). The other settings cannot be verified from
logs alone:

- `beacon.queue.max_size` — verify via the metric
  `beacon.export.queue.capacity` (or watch `beacon.export.dropped_total`
  under load).
- `OTEL_EXPORTER_OTLP_HEADERS` — verify by checking that authenticated
  requests reach your backend (Grafana Cloud's "ingestion logs" tab,
  Datadog's "Live tail", etc.).
- `OTEL_RESOURCE_ATTRIBUTES` — verify by querying any exported metric
  in the backend and confirming the resource labels are attached.

These three gaps are tracked for v0.2.

## Examples

### Minimal (default local backend)

The config Beacon assumes when no file or env vars are set. Equivalent
to running with no `config.toml` at all.

```toml
[otel.exporter.otlp]
endpoint = "http://localhost:4317"
protocol = "grpc"

[otel.service]
name = "hytale-server"
```

### Authenticated (Grafana Cloud-style)

The Grafana Cloud OTLP endpoint requires basic auth. The token goes in
an env var, not the file, because `OTEL_EXPORTER_OTLP_HEADERS` has no
TOML equivalent.

```toml
[otel.exporter.otlp]
endpoint = "https://otlp-gateway-prod-eu-west-2.grafana.net/otlp"
protocol = "http/protobuf"

[otel.service]
name = "hytale-eu-west-pvp-01"
```

```bash
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <base64(instance_id:api_token)>"
```

The base64 string is `printf '%s' "<instance_id>:<api_token>" | base64`
on Linux/macOS. Replace `<instance_id>` and `<api_token>` with the
values from your Grafana Cloud OTLP integration page.

### Custom service identity

Useful for fleets: namespace groups servers by realm, environment
distinguishes prod vs staging, instance.id pins each server.

```toml
[otel.service]
name = "hytale-realm-alpha"
```

```bash
export OTEL_RESOURCE_ATTRIBUTES="service.namespace=mythlane,deployment.environment=prod,service.instance.id=eu-west-pvp-01"
```

`service.namespace` and `deployment.environment` are standard OTel
semantic conventions. The full list is at
<https://opentelemetry.io/docs/specs/semconv/resource/>.

## Reloading configuration

In v0.1, configuration is read once at server boot. Changes to the
TOML file or environment variables take effect only after a server
restart.

Hot reload is planned for v0.3 (cahier des charges sub-phase 3.5).
There are no intermediate workarounds.

## Validation against your environment

After editing your config:

1. Restart the Hytale server.
2. Search the server log for `Beacon OpenTelemetry SDK ready:` — the
   line includes `endpoint=` and `protocol=` and must match your
   intended values.
3. If the values do not match, look for `WARN Beacon config load
   failed` earlier in the log; the stack trace explains why.
4. Wait ~30 s (one export cycle), then open the backend and confirm
   metrics with your `service.name` are arriving. If not, see
   "No data" in the [getting-started troubleshooting](getting-started.md#troubleshooting).

For settings that are not echoed in the startup log
(`beacon.queue.max_size`, headers, resource attributes), follow the
verification approach in
[Limitations of v0.1 startup logging](#limitations-of-v01-startup-logging).
