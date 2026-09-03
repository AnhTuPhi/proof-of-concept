#!/usr/bin/env bash
# Tear down AND drop volumes. Use when EMQX/postgres state is corrupted.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down -v
