# Trade Volume Aggregator

## Problem statement

Aggregate traded volume per user using events from Aeron and Kafka. Maintain off-heap aggregates and emit periodic volume snapshots to Aeron and Kafka.

## Prerequisites

- Java 17+
- Maven 3.8+
- A running Kafka broker (default: `localhost:9092`)
- Aeron MediaDriver is started embedded; no external process required

## Build

```bash
mvn clean package
```

This produces a fat JAR at `target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar`.

## Run

```bash
java -jar target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar
```

## Configuration

Settings are loaded from `src/main/resources/application.properties` and can be overridden via environment variables (replace dots with underscores, uppercase).

| Property | Default | Description |
|---|---|---|
| `kafka.bootstrap.servers` | `localhost:9092` | Kafka broker addresses |
| `kafka.consumer.topic` | `trade-events` | Topic for incoming trade events |
| `kafka.producer.topic` | `volume-snapshots` | Topic for outgoing snapshots |
| `kafka.group.id` | `volume-aggregator-group` | Kafka consumer group ID |
| `aeron.subscriber.channel` | `aeron:udp?endpoint=localhost:40123` | Aeron channel for incoming trades |
| `aeron.subscriber.stream.id` | `1001` | Aeron stream ID for incoming trades |
| `aeron.publisher.channel` | `aeron:udp?endpoint=localhost:40124` | Aeron channel for outgoing snapshots |
| `aeron.publisher.stream.id` | `1002` | Aeron stream ID for outgoing snapshots |
| `snapshot.interval.ms` | `1000` | Snapshot emission interval (ms) |
| `chronicle.map.entries` | `1000000` | Max entries in off-heap map |
| `chronicle.map.file` | `/tmp/volume-aggregator.dat` | Chronicle Map persistence file |

## Project Structure

```
trade-volume-aggregator/
├── pom.xml
├── src/main/java/com/coindcx/aggregator/
│   ├── App.java                          # Entry point, lifecycle management, shutdown hook
│   ├── config/
│   │   └── AppConfig.java                # Loads properties with env-var override support
│   ├── model/
│   │   ├── TradeEvent.java               # Immutable inbound trade event
│   │   └── VolumeSnapshot.java           # Mutable snapshot value stored in Chronicle Map
│   ├── consumer/
│   │   ├── AeronTradeSubscriber.java     # Aeron FragmentHandler, DirectBuffer decoding
│   │   └── KafkaTradeConsumer.java       # Kafka poll loop, JSON deserialization
│   ├── aggregator/
│   │   └── VolumeAggregator.java         # Off-heap Chronicle Map with atomic compute()
│   ├── publisher/
│   │   ├── AeronSnapshotPublisher.java   # DirectBuffer encoding, backpressure retry
│   │   └── KafkaSnapshotPublisher.java   # JSON snapshot emission to Kafka
│   └── snapshot/
│       └── SnapshotEmitter.java          # ScheduledExecutorService, 1s interval
└── src/main/resources/
    ├── application.properties            # Default configuration
    └── logback.xml                       # Logging configuration
```

## Architecture

```
Aeron Subscriber ──┐
                   ├──▶ VolumeAggregator (Chronicle Map, off-heap)
Kafka Consumer  ───┘              │
                          SnapshotEmitter (every 1s)
                           ┌──────┴──────┐
                     Kafka Producer   Aeron Publisher
```

### Aeron Wire Formats

**Trade event (inbound):**

| Offset | Field | Type |
|--------|-------|------|
| 0 | userId | long (8B) |
| 8 | volume | double (8B) |
| 16 | timestampMs | long (8B) |
| 24 | symbolLen | int (4B) |
| 28 | symbol | UTF-8 (symbolLen B) |

**Volume snapshot (outbound):**

| Offset | Field | Type |
|--------|-------|------|
| 0 | userId | long (8B) |
| 8 | totalVolume | double (8B) |
| 16 | lastUpdatedMs | long (8B) |
| 24 | snapshotTimestampMs | long (8B) |

### Kafka Message Formats

Both inbound trade events and outbound snapshots use JSON serialization. The Kafka message key is the `userId` as a string.
