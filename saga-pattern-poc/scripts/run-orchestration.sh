#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p logs
echo "Starting orchestrator-service (logs/orchestrator-service.log)"
( cd "orchestration/orchestrator-service" && mvn -q spring-boot:run ) | tee logs/orchestrator-service.log
