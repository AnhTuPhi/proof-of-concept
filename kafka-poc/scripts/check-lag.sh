#!/usr/bin/env bash
# Quick consumer-group lag inspection. The number to watch in production.
set -euo pipefail

BROKER=${KAFKA_BROKER:-localhost:9092}
KAFKA=${KAFKA_DOCKER:-kafka}

if [ "${1:-}" = "--all" ] || [ -z "${1:-}" ]; then
  echo "=== All consumer groups ==="
  docker exec "$KAFKA" /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server "$BROKER" --list
  echo
  for g in $(docker exec "$KAFKA" /opt/kafka/bin/kafka-consumer-groups.sh \
                 --bootstrap-server "$BROKER" --list); do
    echo "--- $g ---"
    docker exec "$KAFKA" /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server "$BROKER" --group "$g" --describe || true
    echo
  done
else
  docker exec "$KAFKA" /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server "$BROKER" --group "$1" --describe
fi
