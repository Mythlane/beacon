# Security and privacy

Threat-model summary, secure-configuration guidance, and the privacy posture
of the v0.1.0-alpha release.

## Disclosure

If you find a security issue, do not open a public GitHub issue. Email
`socials@mythlane.com` with the subject prefix `[Beacon security]` and a
reproduction. We will acknowledge within 72 hours.

## Configuration

OTLP endpoints frequently carry bearer tokens or basic-auth credentials in
headers (`OTEL_EXPORTER_OTLP_HEADERS`). Treat them as secrets:

- Never commit `config.toml` containing a populated
  `OTEL_EXPORTER_OTLP_HEADERS` value. Add the file to `.gitignore` and ship
  only `config.example.toml` with placeholder values.
- Prefer environment variables over file-based config in production. Env
  vars are easier to inject from a secret manager (HashiCorp Vault, AWS SSM)
  and do not survive in disk backups.
- Beacon's config precedence is env > file > default. A leaked `config.toml`
  cannot override a properly-set env var, but a leaked one is still a leaked
  credential; rotate immediately.

## Error handling

Beacon catches all lifecycle exceptions and degrades to a no-op SDK rather
than crashing the Hytale server:

- `BeaconPlugin.setup()` failure: `OpenTelemetry.noop()` is installed; the
  server keeps booting.
- OTLP export failure: bounded in-memory queue (16 384 entries) plus circuit
  breaker. After N consecutive failures the breaker opens, drops new
  spans/metrics, and emits a WARN log at most once per 60 seconds.
- The drop count is exposed as `beacon.export.dropped_total`.

A telemetry plugin must never take down the game.

## Privacy posture (v0.1.0-alpha)

Beacon emits only:

- `hytale.world.uuid`: stable world identifier from `World.getUuid()`.
- `hytale.world.name`: human-readable world name.
- Numeric counts and gauges (TPS, MSPT, players online).

Beacon does NOT emit:

- Player UUIDs.
- Player display names.
- Chat content, command arguments, or any text input.
- IP addresses.
- Login/auth secrets.

UUID hashing for deployment in RGPD-strict environments will ship in a later
release. Until then, do not enable per-player metrics in environments subject
to GDPR Article 4(1) without your own DPIA.

## Cardinality safeguards

Unbounded label cardinality is the most common operational hazard with OTLP
metrics in production. Beacon's defaults:

- Per-world labels only. Typical 1-5 worlds * 2 attributes is bounded.
- No per-player metrics in v0.1.
- Allowlist-based attribute filter in the OTel SDK Views; additional
  attributes are dropped, not silently propagated.
