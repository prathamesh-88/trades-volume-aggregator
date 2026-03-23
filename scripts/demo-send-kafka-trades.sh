#!/usr/bin/env bash
# Send sample TradeEvent JSON lines to trade-events (requires Kafka from docker compose).
set -euo pipefail
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
TOPIC="${KAFKA_TRADE_TOPIC:-trade-events}"
CONTAINER="${KAFKA_CONTAINER:-trade-volume-kafka}"

if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  PRODUCER=(docker exec -i "$CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$TOPIC")
else
  if ! command -v kafka-console-producer.sh &>/dev/null; then
    echo "Neither Docker container '$CONTAINER' nor kafka-console-producer.sh found." >&2
    exit 1
  fi
  PRODUCER=(kafka-console-producer.sh --bootstrap-server "$BOOTSTRAP" --topic "$TOPIC")
fi

ts="$(python3 -c 'import time; print(int(time.time()*1000))' 2>/dev/null || echo "$(($(date +%s) * 1000))")"

for i in 1 2 3; do
  echo "{\"userId\":1001,\"symbol\":\"BTCUSDT\",\"volume\":0.25,\"timestampMs\":$((ts + i))}"
  echo "{\"userId\":1002,\"symbol\":\"ETHUSDT\",\"volume\":1.0,\"timestampMs\":$((ts + i))}"
done | "${PRODUCER[@]}"

echo "Sent demo trades to $TOPIC at $BOOTSTRAP"
