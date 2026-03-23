#!/usr/bin/env bash
# Consume volume-snapshots JSON (Ctrl+C to stop). Requires Kafka from docker compose.
set -euo pipefail
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
TOPIC="${KAFKA_SNAPSHOT_TOPIC:-volume-snapshots}"
CONTAINER="${KAFKA_CONTAINER:-trade-volume-kafka}"

if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  exec docker exec -it "$CONTAINER" /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic "$TOPIC" \
    --from-beginning \
    --property print.key=true
else
  exec kafka-console-consumer.sh \
    --bootstrap-server "$BOOTSTRAP" \
    --topic "$TOPIC" \
    --from-beginning \
    --property print.key=true
fi
