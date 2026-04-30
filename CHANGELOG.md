# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: pre-1.0 may break between alphas; strict semver from `1.0.0`.

## [Unreleased] - v0.1.0-rc

### Refactoring

- **BeaconPluginLifecycle decomposition** (graphify-driven). The Hytale-agnostic
  plugin lifecycle has been decomposed from a single god object (degree 13,
  betweenness 0.259) into four single-responsibility collaborators. The
  orchestrator drops from ~270 lines to ~135 lines.
  - `ConfigBootstrap` — config loading with documented fallback to defaults.
    Never throws.
  - `PollScheduler` — daemon executor lifecycle with idempotent cancel and
    double-start guard.
  - `ShutdownCoordinator` — idempotent shutdown with shared 5s deadline
    between tracer and meter forceFlush.
  - `TelemetryBootstrap` — OpenTelemetry SDK construction and instrument
    binding. Returns `InstrumentHandles` record exposing the three runtime
    handles. Never throws (individual fallbacks).
- **Behavior change**: shutdown worst-case wall-clock reduced from ~10s
  (5s per flush) to ~5s (single shared deadline).
- All public APIs of `BeaconPlugin` and `BeaconPluginLifecycle` preserved.
  `BeaconLifecycleTest` unchanged across the four refactor commits.

### Architecture metrics (graphify --update)

- BeaconPluginLifecycle betweenness: 0.259 → 0.078 (−70%, target <0.10 met).
- BeaconPluginLifecycle dropped from god-node #1 to #6.
- Communities: 18 → 23 (+5, each new component anchoring its own cluster).
- `TelemetryBootstrap` betweenness 0.10 — most structural of the new components,
  to monitor as Phase 2 lands.

### Known Limitations

CdC sub-phase items planned for v0.1 but deferred to v0.2. None of these block
the v0.1.0 release; each has a documented workaround.

- **`beacon.enabled` / `BEACON_ENABLED` kill switch (CdC sub-phase 1.x).** Not
  implemented. Beacon is always active when the JAR is present. Workaround:
  remove `beacon-0.1.0.jar` from `mods/` and restart to disable.
- **`beacon.metrics.interval` / `BEACON_METRICS_INTERVAL` (CdC sub-phase 1.x).**
  Export interval hardcoded to 30 s in `PeriodicMetricReader`. No workaround
  in-process; backends needing a different cadence must reaggregate at a
  collector.
- **Auto-creation of `config.toml` on first boot with commented defaults
  (CdC sub-phase 1.3).** Not implemented. Beacon runs on built-in defaults if
  the file is missing, no log warning, no scaffolding written. Workaround:
  create `mods/Mythlane_Beacon/config.toml` by hand using the example in
  `docs/configuration.md`.
- **Startup log echoes only 3 of the supported settings.** `endpoint`,
  `serviceName`, `protocol` are logged at boot; `beacon.queue.max_size`,
  `OTEL_EXPORTER_OTLP_HEADERS`, and `OTEL_RESOURCE_ATTRIBUTES` cannot be
  verified from logs alone. Verify via metrics or backend-side inspection
  (see `docs/configuration.md` "Limitations of v0.1 startup logging").

## [0.1.0-alpha] - 2026-04-29

First public pre-release.

### Added

- **FND-01** Plugin lifecycle (`setup` / `start` / `shutdown` / `cleanup`)
  with a defensive `GlobalOpenTelemetry` probe and a 5-second shutdown flush.
- **FND-02** Configuration via env vars
  (`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`,
  `OTEL_EXPORTER_OTLP_PROTOCOL`) or `config.toml`, with precedence
  env > file > default.
- **FND-03** OTel Java Agent extension (`beacon-agent.jar`) shipping JVM
  auto-instrumentation and resource-attribute injection through the agent's
  extension classloader.
- **FND-04** `hytale.tps` (gauge) and `hytale.mspt` (histogram), per world,
  polled every 30 s from the existing Hytale `HistoricMetric.getLastValue()`.
- **FND-05** `hytale.players.online` per-world counter, idempotent on
  `PlayerDisconnectEvent`.
- **FND-06** Self-contained LGTM example stack in `examples/lgtm-stack/`
  (Tempo + Loki + Mimir + Pyroscope + OpenTelemetry Collector + Grafana,
  all auto-provisioned).
- **FND-07** Server Health Grafana dashboard (7 panels, TPS red threshold 27).
- **FND-08** Bench harness (`bench/`) reproducing the 11-step Hytale QUIC
  client session, measuring < 1% CPU and < 50 MB resident memory at
  20 simulated players over 10 minutes (3-run median).
- **FND-09** README quickstart, 6 steps, < 5 minutes on a fresh machine.
  Validation procedure documented in `docs/quickstart-validation.md`.
- **FND-10** v0.1.0-alpha published on CurseForge with both
  `beacon-0.1.0-alpha.jar` and `beacon-agent-0.1.0-alpha.jar`.
- **REL-01** CurseForge release tagged Alpha with the pre-release statement
  in the title and the quickstart in the description.
- **REL-02** This `CHANGELOG.md`, enforced by `ChangelogTest`.

### Notes

- TPS target 30, red threshold 27.
- MSPT is sourced from the existing Hytale `HistoricMetric`; Beacon does not
  install a custom timing `Runnable` on the server thread.
- Sentry 8.29.0 is bundled in Hytale but does not register a
  `GlobalOpenTelemetry` SDK by default (mode `AUTO` falls back to no-op).
- `PlayerDisconnectEvent` fires once; the idempotent handler is retained
  defensively against future SDK changes.

### Known Limitations

- No on-disk buffer for OTLP failures in v0.1; a bounded in-memory queue plus
  circuit breaker drops new spans/metrics and exposes the drop count via
  `beacon.export.dropped_total`.
- The OTel Java Agent must be attached at boot (`-javaagent:`); hot-attach is
  not supported.
- License: MIT pre-1.0; commercial monetization is deferred to v1.0+.
