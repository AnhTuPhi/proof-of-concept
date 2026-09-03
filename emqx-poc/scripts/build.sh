#!/usr/bin/env bash
# Build all POC modules. -T 1C for parallel builds.
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -T 1C -DskipTests clean package "$@"
