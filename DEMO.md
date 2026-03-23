# End-to-end demo — Trade Volume Aggregator

This guide walks through demonstrating the application **after** infrastructure and the JAR are ready. It assumes you have completed [SETUP.md](SETUP.md) (JDK, Maven, Docker) and understand the architecture in [README.md](README.md).

---

## What you are demonstrating

1. **Inbound trades** enter the system via **Kafka** (`trade-events`, JSON) and/or **Aeron** (UDP binary on port **40123**).
2. **VolumeAggregator** (Chronicle Map) accumulates **total volume per `userId`**.
3. On each **snapshot interval** (default **1s**), **SnapshotEmitter** publishes **VolumeSnapshot** JSON to **`volume-snapshots`** and binary frames to **Aeron** UDP **40124**.
4. Logs show lines like **`Emitted N snapshots`** when a tick runs.

---

## Preconditions

| Check | Command / note |
|--------|----------------|
| Kafka (Compose) | `docker compose ps` shows `trade-volume-kafka` **running**; topics `trade-events` and `volume-snapshots` exist (created by `kafka-init`). |
| Kafka UI (optional) | [http://localhost:8080](http://localhost:8080) — cluster **local**. |
| Fat JAR built | `mvn clean package -DskipTests` (or full `mvn package`) → `target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar`. |
| `java` on PATH | `java -version` must point at a real JDK (not macOS `/usr/bin/java` stub). |
| UDP / Aeron | Aggregator embeds MediaDriver; inbound **40123** and outbound **40124** must be free on the host (do **not** run Compose **`aeron-media-driver`** profile on the same machine). |
| Chronicle file | Use a clean file for a clean demo, e.g. `export CHRONICLE_MAP_FILE=/tmp/demo-volume.dat` and remove the file before demo if you want an empty map. |

---

## Part A — Bring up dependencies and the app

### 1. Start Kafka (and optional UI)

From the repository root:

```bash
docker compose up -d kafka kafka-init kafka-ui
```

Wait until `kafka-init` has finished (check `docker compose logs kafka-init` for **Kafka topics ready.**).

### 2. Build and run the aggregator

```bash
mvn clean package -DskipTests
export CHRONICLE_MAP_FILE=/tmp/demo-volume.dat
rm -f "$CHRONICLE_MAP_FILE"

java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
  -jar target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar
```

Leave this terminal open. You should see logs similar to:

- Embedded **Aeron MediaDriver** started  
- **Kafka consumer** subscribed to `trade-events`  
- **Snapshot emitter** started with interval **1000ms**  
- Every second (when there is data): **`Emitted <n> snapshots in …ms`**

### 3. Stop the demo

- **Ctrl+C** in the JAR terminal (shutdown hook closes resources), or send **SIGTERM**.  
- Optional: `docker compose down` when finished with Kafka.

---

## Part B — Manual testing

### B1. Produce trades with Kafka UI

1. Open [http://localhost:8080](http://localhost:8080).
2. Select cluster **local** → **Topics** → **`trade-events`**.
3. Use **Produce message** (or equivalent) and send **one message per line**, **value** = JSON (key optional):

```json
{"userId":42,"symbol":"BTCUSDT","volume":0.5,"timestampMs":1730000000000}
```

4. Send a few messages with the same **`userId`** and different **`volume`** values to show aggregation.
5. Open topic **`volume-snapshots`**, **consume** from beginning (or latest after a few seconds). You should see JSON objects with **`userId`**, **`totalVolume`**, **`lastUpdatedMs`**, **`snapshotTimestampMs`**.
6. Watch the **aggregator terminal**: after each interval you should see **`Emitted … snapshots`**; `n` matches the number of distinct users currently in the map.

### B2. Produce trades with `kafka-console-producer` (Docker)

If the broker runs in Compose (`trade-volume-kafka`):

```bash
echo '{"userId":7,"symbol":"ETHUSDT","volume":2.0,"timestampMs":1730000001000}' | \
  docker exec -i trade-volume-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic trade-events
```

Repeat with the same `userId` and different `volume` to increase **`totalVolume`** in the next snapshot.

### B3. Consume snapshots manually

```bash
docker exec -it trade-volume-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic volume-snapshots \
  --from-beginning \
  --property print.key=true
```

Keys should match **`userId`** as string; values are **VolumeSnapshot** JSON.

### B4. Aeron inbound trade (manual, one datagram)

With the **aggregator running**, send one binary frame to **UDP 40123** using the helper (requires **Python 3**):

```bash
./scripts/aeron_send_trade.py --user-id 99 --volume 1.25 --symbol "DEMO"
```

Check logs for absence of decode warnings; after the next snapshot tick, **`volume-snapshots`** should include **`userId`** `99` if Kafka path also fed data, or only Aeron-fed users appear in emitted counts as applicable.

**Note:** There is no bundled Aeron *subscriber* UI; validating Aeron output usually means a small custom subscriber or external tooling. The **Kafka** snapshot stream is the easiest way to confirm end-to-end aggregation for a mixed demo.

### B5. Negative / edge checks (optional)

- **Invalid JSON** on `trade-events`: producer should log a **deserialise** warning; aggregator should not crash.  
- **Duplicate users**: multiple messages same `userId` → **`totalVolume`** increases in snapshots.

---

## Part C — Script-based testing

Scripts live under **`scripts/`**. Make them executable once:

```bash
chmod +x scripts/demo-send-kafka-trades.sh scripts/demo-consume-snapshots.sh scripts/aeron_send_trade.py
```

### C1. Batch-send Kafka trades

Sends several valid **`TradeEvent`** JSON lines to **`trade-events`**:

```bash
./scripts/demo-send-kafka-trades.sh
```

Environment overrides (optional):

| Variable | Default | Purpose |
|----------|---------|---------|
| `KAFKA_CONTAINER` | `trade-volume-kafka` | Docker container name for `kafka-console-producer` |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Used only if **not** using Docker producer |
| `KAFKA_TRADE_TOPIC` | `trade-events` | Topic name |

Run the script **while the JAR is running**, wait **≥ 1 snapshot interval**, then consume snapshots (C2 or B3).

### C2. Watch snapshot topic (script)

```bash
./scripts/demo-consume-snapshots.sh
```

Uses the same container detection as C1. **Ctrl+C** to exit. You should see snapshot messages after trades were ingested.

### C3. One-liner loop (bash only)

Send five trades for user **5001** without helper scripts:

```bash
for i in 1 2 3 4 5; do
  ts=$(($(date +%s) * 1000 + i))
  echo "{\"userId\":5001,\"symbol\":\"BTCUSDT\",\"volume\":0.1,\"timestampMs\":$ts}" | \
    docker exec -i trade-volume-kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic trade-events
done
```

### C4. Aeron + Kafka combined script flow

```bash
# Terminal 1: aggregator already running
# Terminal 2:
./scripts/demo-send-kafka-trades.sh
./scripts/aeron_send_trade.py --user-id 3001 --volume 10 --symbol "MIX"
sleep 3
./scripts/demo-consume-snapshots.sh
```

Expect snapshot messages for users **1001**, **1002** (from demo-send script), **3001** (Aeron), and any prior users still in the Chronicle map.

### C5. Automated smoke (CI-style)

From repo root, non-interactive checks:

```bash
docker compose up -d kafka kafka-init
# wait for topics
until docker exec trade-volume-kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092 >/dev/null 2>&1; do sleep 2; done

mvn -q clean package -DskipTests
export CHRONICLE_MAP_FILE=/tmp/ci-demo-volume.dat
rm -f "$CHRONICLE_MAP_FILE"
java -jar target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar &
APP_PID=$!
sleep 5
./scripts/demo-send-kafka-trades.sh
sleep 3
docker exec trade-volume-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic volume-snapshots \
  --from-beginning \
  --max-messages 5 \
  --timeout-ms 15000
kill $APP_PID
wait $APP_PID 2>/dev/null || true
docker compose down
```

Adjust **`--max-messages`** / **`sleep`** if the broker or host is slow.

---

## Expected results checklist

| Step | Expected |
|------|-----------|
| JAR startup | No fatal errors; Kafka subscribe + snapshot scheduler logs. |
| JSON trade to `trade-events` | Consumer thread processes; optional warn only on bad JSON. |
| After ~1s | Log: **`Emitted N snapshots`** with **N ≥ 1** if map non-empty. |
| `volume-snapshots` | JSON with **`totalVolume`** reflecting sum of volumes per **`userId`**. |
| `aeron_send_trade.py` | No **Failed to decode Aeron trade fragment** in logs for valid payload. |
| Shutdown | Shutdown hook logs; process exits; Docker can be stopped. |

---

## Reference — JSON shapes

**TradeEvent** (Kafka `trade-events` value):

```json
{
  "userId": 1,
  "symbol": "BTCUSDT",
  "volume": 0.5,
  "timestampMs": 1730000000000
}
```

**VolumeSnapshot** (Kafka `volume-snapshots` value):

```json
{
  "userId": 1,
  "totalVolume": 1.5,
  "lastUpdatedMs": 1730000000000,
  "snapshotTimestampMs": 1730000001500
}
```

---

## Troubleshooting

| Issue | Suggestion |
|-------|------------|
| Cannot connect to Kafka | `docker compose ps`; broker on **localhost:9092**; see SETUP.md. |
| No snapshots / N = 0 | No trades ingested yet, or empty Chronicle map; send trades and wait one interval. |
| Chronicle / reflection errors on `java -jar` | Add JVM `--add-opens` / `--add-exports` as in SETUP.md / `pom.xml` Surefire args. |
| Aeron decode warnings | Verify little-endian layout and **UDP 40123**; see README wire format table. |
| Port 40123/40124 in use | Stop conflicting process or optional Compose `aeron-media-driver` profile. |

For local environment setup, see **[SETUP.md](SETUP.md)**.
