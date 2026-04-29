#!/usr/bin/env bash
# Beacon LGTM stack — smoke test (FND-06 verification).
#
# Polls each datasource health endpoint until ready, with a 60s deadline.
# Runs from the host (so all checks target localhost — ports bound to 127.0.0.1).
#
# Usage: bash scripts/smoke-test.sh
set -euo pipefail

timeout="${TIMEOUT:-60}"
deadline=$((SECONDS + timeout))

# Each entry: "label|url"
endpoints=(
  "tempo|http://localhost:3200/ready"
  "loki|http://localhost:3100/ready"
  "mimir|http://localhost:9009/ready"
  "pyroscope|http://localhost:4040/ready"
  "otel-collector|http://localhost:13133/"
  "grafana|http://localhost:3000/api/health"
)

for entry in "${endpoints[@]}"; do
  label="${entry%%|*}"
  url="${entry#*|}"
  while ! curl -fsS "$url" >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "FAIL: ${label} (${url}) not ready in ${timeout}s"
      exit 1
    fi
    sleep 1
  done
  echo "OK: ${label} (${url})"
done

echo "All datasources healthy."
