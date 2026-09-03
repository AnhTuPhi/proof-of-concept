#!/usr/bin/env bash
# One-shot bootstrap: bring infra up, create topics, register connectors.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "== 1/4 Starting infrastructure (this may take ~3 min for Oracle on first run) =="
docker compose --profile all up -d

echo "== 2/4 Waiting for Kafka =="
until docker exec kafka /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server localhost:9092 --list >/dev/null 2>&1; do
  sleep 2; echo -n "."
done
echo " ok"

echo "== 3/4 Creating topics =="
./scripts/create-topics.sh

echo "== 4/4 Waiting for Kafka Connect (Debezium download) =="
until curl -fsS http://localhost:8083/ >/dev/null 2>&1; do
  sleep 3; echo -n "."
done
echo " ok"

echo
echo "Skipping connector registration here. When Oracle is healthy run:"
echo "  ./scripts/register-connectors.sh"
echo
echo "Useful endpoints:"
echo "  - Kafka UI:        http://localhost:8080"
echo "  - Schema Registry: http://localhost:8081"
echo "  - Kafka Connect:   http://localhost:8083"
echo "  - Kibana:          http://localhost:5601"
echo "  - Prometheus:      http://localhost:9090"
echo "  - Grafana:         http://localhost:3000"
