# Spike: QUIC Client Library Selection (B1 / D-11)

**Date:** 2026-04-28
**Owner:** Beacon Phase 1 / Plan 01-01 / Task 3
**Goal:** Pick the QUIC client library that powers the `bench/` bot for FND-08
overhead measurement against `HytaleServer.jar`.

## Method

Constraint-driven evaluation rather than full PoC integration in this Wave 0
plan. Three candidates were compared on six axes that determine whether they
can drive the 11-step Hytale handshake (see
`.planning/research/BENCH-HARNESS.md` §"Reconstructed Client Session Sequence")
across Windows + Linux on JDK 25 with < 1-day onboarding cost.

Hands-on PoC against `127.0.0.1:5520` is deferred to Plan 07 (bench
implementation). The three pre-plan blockers (B1/B2/B3) only need the
**decision locked** before downstream plans are written, not a working bot.

## Candidate Matrix

| Axis | Netty incubator QUIC<br/>`io.netty:netty-codec-classes-quic` | msquic-java<br/>(Microsoft msquic JNI) | Jetty QUIC<br/>`org.eclipse.jetty.quic` |
|---|---|---|---|
| Maintenance | Active, in `netty-incubator-codec-quic`; Netty core team | Microsoft maintains `msquic` (Rust); Java bindings community-maintained, sparse releases | Eclipse Jetty 12+ ships QUIC for HTTP/3; mature core |
| API ergonomics from Java | Excellent — Netty's `Bootstrap`/`ChannelHandler` model is idiomatic Java; pairs cleanly with virtual-thread Executors | Acceptable — JNI surface, manual buffer management | Designed around HTTP/3; raw QUIC stream API is internal/unstable |
| Cross-platform native libs | `netty-codec-classes-quic` + `netty-codec-native-quic` classifier per OS (linux-x86_64, osx-x86_64, windows-x86_64). Bundled in Maven artifacts. | Requires shipping `msquic.dll` (Win) + `libmsquic.so` (Linux) per OS via separate native packaging | Pure Java + relies on JDK 22+ HTTPClient (limited); native QUIC not bundled |
| Drives raw QUIC handshake (no HTTP) | Yes — `QuicChannel` / `QuicStreamChannel` direct stream API | Yes — full QUIC API exposed | **No** — public API is HTTP/3-only; raw stream access requires reflection into internal classes |
| JDK 25 compat | Yes (Netty 4.2 line builds on Java 24/25; `-Dnet.bytebuddy.experimental=true` already in our test config) | Yes (JNI is JDK-version-agnostic) | Yes (Jetty 12 supports Java 21+) |
| License | Apache 2.0 | MIT | Apache 2.0 / EPL 2.0 dual |
| Last release (as of 2026-04) | Netty incubator QUIC 0.0.69.Final (rolling) | msquic-java 0.x — irregular | Jetty 12.0.x — frequent |
| Onboarding cost (raw QUIC echo) | ~1 day | ~2 days (JNI buffer plumbing) | Not applicable for raw QUIC |

## Decision Rationale

Hytale's session is **raw QUIC carrying a custom binary protocol** (not HTTP/3).
That single fact eliminates Jetty: its public API does not expose
post-handshake stream framing without reflecting into internal classes, which
is brittle and would need to be revisited every Jetty release.

Between Netty incubator QUIC and msquic-java:
- **Netty wins** on Java ergonomics (we already use Netty patterns
  elsewhere in the Mythlane ecosystem; the Beacon team already shades Netty
  per `CLAUDE.md`'s shading directives).
- **Netty wins** on native lib bundling — Maven classifiers ship Windows +
  Linux + macOS binaries, no separate packaging step.
- **Netty wins** on incremental adoption — the bench module already plans to
  shade Netty defensively (Hytale bundles its own copy). Adding a second QUIC
  stack (msquic-java) doubles the native dependency footprint.

The only place msquic-java would win is if a future Hytale server release
exposed Microsoft-specific QUIC extensions; that is highly unlikely and easy
to revisit later.

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Netty incubator QUIC API may shift in 0.x line | Pin exact version in `libs.versions.toml`; Plan 07 freezes the wire-level integration in tests |
| `netty-codec-native-quic` adds ~6 MB of native libs to bench distribution | Bench is dev-only, never shipped to users; size acceptable |
| Hytale server uses an unusual QUIC variant (e.g., MsQuic-specific transport params) | If discovered, reopen this decision and fall back to msquic-java; Plan 07 spike will catch this |

## D-11 (Locked)

**D-11 (Locked):** QUIC library = **Netty incubator QUIC**
(`io.netty.incubator:netty-incubator-codec-quic` for the bench bot, with
`netty-codec-native-quic` native classifier per OS).

Plan 07 will pin the exact version after a one-day PoC against
`127.0.0.1:5520` confirms the handshake flows end-to-end.

**Status:** RESOLVED — Netty incubator QUIC selected for raw-QUIC API access, Java ergonomics, and bundled cross-platform native libs.
