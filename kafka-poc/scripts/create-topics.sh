#!/usr/bin/env bash
# Create every topic the POCs use with PRODUCTION-LEANING configs.
# Partition counts are sized for "easy to demo" not "max throughput";
# bump for load testing.
set -euo pipefail

BROKER=${KAFKA_BROKER:-localhost:9092}
KAFKA=${KAFKA_DOCKER:-kafka}

run() {
  docker exec "$KAFKA" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BROKER" "$@"
}

create() {
  local topic=$1
  local partitions=${2:-6}
  local rf=${3:-1}
  local extra=${4:-}
  echo "Creating topic: $topic (partitions=$partitions rf=$rf)"
  run --create --if-not-exists --topic "$topic" \
      --partitions "$partitions" --replication-factor "$rf" \
      ${extra:+$extra} || true
}

# Core business topics
create orders.placed.v1            12 1 "--config min.insync.replicas=1 --config retention.ms=604800000"
create orders.paid.v1              12 1
create orders.shipped.v1           12 1
create orders.cancelled.v1         12 1

# Payments / inventory / shipping (saga)
create payments.requested.v1        6 1
create payments.completed.v1        6 1
create payments.failed.v1           6 1
create inventory.reserve.requested.v1 6 1
create inventory.reserved.v1        6 1
create inventory.reserve.failed.v1  6 1
create shipping.requested.v1        6 1
create shipping.completed.v1        6 1

# Streams demo
create clickstream.events.v1       12 1
create clickstream.windowed.v1     12 1
create clickstream.enriched.v1     12 1
create clickstream.attributed.v1   12 1
create users.profile.v1             6 1 "--config cleanup.policy=compact --config min.cleanable.dirty.ratio=0.1"

# Avro / Schema Registry demo
create orders.placed.v1.avro       12 1

# Compacted state / event-sourced demos
create event-store.orders          12 1 "--config cleanup.policy=compact --config retention.ms=-1"

echo
echo "Listing topics:"
run --list
