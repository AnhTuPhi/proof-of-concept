#!/usr/bin/env bash
# Tear everything down (keeps named volumes).
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down
