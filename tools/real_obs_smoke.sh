#!/usr/bin/env bash
# Disposable real OBS only: no host home, configuration, devices or display mounts.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/real_obs_smoke.py
