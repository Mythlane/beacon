# Quickstart validation procedure

A manual stopwatch procedure that must be executed before each Beacon
alpha/beta/release tag to confirm the README quickstart still completes in
under 5 minutes on a fresh machine. A failed run blocks the release.

## Why this is manual

The metric is perceived friction for a brand-new admin, which requires a
clean OS image without prior JDK, Docker, or shell history priming the
environment. CI cannot reproduce that condition cheaply.

## Prerequisites (provisioned ahead of timing)

Not counted against the 5-minute budget; documented in the README preamble.

- A fresh VM image (Windows 11 Pro or Ubuntu 24.04 LTS, both validated).
- 16 GB RAM, 4 vCPUs minimum.
- JDK 25 installed and on `PATH`.
- Docker Desktop (Windows) or Docker Engine (Linux) installed and running.
- Network connection capable of pulling the LGTM container images.

## Procedure

1. Open the Beacon CurseForge page in a browser tab. Have the README
   rendered in another tab.
2. Start the stopwatch.
3. Follow the README Quickstart section verbatim. No deviations, no
   shortcuts, no copy-pasting from other documentation.
4. Stop the stopwatch when the Server Health Grafana dashboard renders with
   at least one panel showing live data (TPS or JVM heap is sufficient).
5. Record the result in the table below.

## Pass / fail criteria

- PASS: total elapsed time <= 5 minutes.
- FAIL: > 5 minutes, OR a step required external knowledge not documented in
  the README, OR an error occurred that the README did not predict.

## Validation runs

| Date | OS | Hardware | Validator | Total Time | Result | Notes |
|------|----|----------|-----------|------------|--------|-------|
| _pending_ | - | - | - | - | - | First run scheduled before CurseForge upload. |

## Known failure modes

- Docker Desktop "WSL2 not enabled" on Windows adds about 3 minutes.
  Documented as a prerequisite, not part of the timed run.
- Port 3000 already bound by another local Grafana; covered in the LGTM
  stack README troubleshooting section.
- Image pull throttled by Docker Hub anonymous limits; log in to Docker Hub
  before starting the stopwatch.
