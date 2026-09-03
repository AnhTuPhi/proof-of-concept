#!/usr/bin/env bash
# Generates a realistic mix of normal / FAIL / POISON messages on
# orders.placed.v1 so the DLQ + retry topics get exercised.
set -euo pipefail

BROKER=${KAFKA_BROKER:-localhost:9092}
KAFKA=${KAFKA_DOCKER:-kafka}
COUNT=${1:-1000}
POISON_PCT=${2:-2}    # 2% poison
FAIL_PCT=${3:-5}      # 5% retriable fail

echo "Producing $COUNT messages (poison=${POISON_PCT}%, fail=${FAIL_PCT}%) -> orders.placed.v1"

for i in $(seq 1 "$COUNT"); do
  r=$((RANDOM % 100))
  if [ "$r" -lt "$POISON_PCT" ]; then
    payload="{\"eventId\":\"$i\",\"payload\":\"POISON malformed\"}"
  elif [ "$r" -lt $((POISON_PCT + FAIL_PCT)) ]; then
    payload="{\"eventId\":\"$i\",\"payload\":\"FAIL transient\"}"
  else
    payload="{\"eventId\":\"$i\",\"aggregateId\":\"order-$i\",\"payload\":{\"customerId\":\"cust-$((i%50))\",\"amount\":$((100 + i)),\"sku\":\"SKU-00$((1 + i%3))\",\"qty\":$((1 + i%5))}}"
  fi
  echo "order-$i:$payload"
done | docker exec -i "$KAFKA" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BROKER" \
  --topic orders.placed.v1 \
  --property "parse.key=true" \
  --property "key.separator=:"

echo "Done."
