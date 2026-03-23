# Local setup — Trade Volume Aggregator

This guide covers prerequisites, starting **Kafka** and optional **Aeron** dependencies with Docker, building the application, configuration, and how it interacts with the **embedded** Aeron Media Driver.

---

## Prerequisites

| Requirement | Notes |
|-------------|--------|
| **Java Development Kit (JDK) 17+** | Same as `maven.compiler.release` in `pom.xml`. Verify: `java -version`. |
| **Apache Maven 3.8+** | For builds. Verify: `mvn -version`. |
| **Docker Engine + Docker Compose v2** | For local Kafka (and optional Aeron driver). Verify: `docker compose version`. |
| **OS / network** | Default Aeron URIs use **UDP** on `localhost` ports **40123** (inbound trades) and **40124** (outbound snapshots). Ensure a firewall allows **UDP** on these ports when producers/consumers run on another host. |

### JVM flags (Chronicle Map)

The app uses **Chronicle Map**, which may require extra module opens on newer JDKs (similar to the flags used in `pom.xml` for tests). If you see errors about `InaccessibleObjectException` or Chronicle’s runtime compiler when starting the JAR, run with the same style of `--add-opens` / `--add-exports` as documented in the [Chronicle support for Java 17+](https://chronicle.software/chronicle-support-java-17) guidance, or align with the `surefire.jvmArgs` block in `pom.xml`.

---

## External dependencies overview

| Dependency | Role in this project | How you run it locally |
|------------|----------------------|-------------------------|
| **Kafka** | Consumer reads **trade** JSON from `trade-events`; producer writes **snapshot** JSON to `volume-snapshots`. | **Docker Compose** (`kafka` + `kafka-init` services). |
| **Aeron** | Subscriber decodes **binary** trades; publisher sends **binary** snapshots over UDP. | The application starts an **embedded** `MediaDriver` (see `AggregatedServices`). You normally **do not** need a separate Aeron server. Optional **Docker** `aeron-media-driver` is for advanced / Docker-only scenarios (see below). |

---

## 1. Start Kafka with Docker Compose

From the repository root:

```bash
docker compose up -d kafka kafka-init kafka-ui
```

- **Kafka** is reachable from your machine at **`localhost:9092`**. Inside Compose, services use **`kafka:19092`** (internal listener) so broker metadata does not point internal clients at `localhost`.
- **`kafka-init`** creates topics if missing:
  - `trade-events`
  - `volume-snapshots`
- **Kafka UI** (optional but recommended for local testing) is at **`http://localhost:8080`**. Use the preconfigured **local** cluster to inspect topics, read messages, and send test records to `trade-events` (JSON matching `TradeEvent` in the README).

If you omit the UI to save resources:

```bash
docker compose up -d kafka kafka-init
```

Check Kafka:

```bash
docker compose ps
docker compose logs -f kafka
```

Stop services:

```bash
docker compose down
```

Optional: remove volumes (this project does not define named Kafka volumes by default; `down` stops containers only).

---

## 2. Aeron and Docker Compose (important)

The application **embeds** an Aeron **MediaDriver** and uses:

- Subscriber: `aeron:udp?endpoint=localhost:40123` (stream `1001` by default)
- Publisher: `aeron:udp?endpoint=localhost:40124` (stream `1002` by default)

So **on a normal laptop run**, the JAR already owns UDP **40123** and **40124** on the host.

The Compose file includes an **optional** service **`aeron-media-driver`** (profile **`aeron`**) that also expects those UDP ports. **Do not** start it on the same machine while running the default app, or you will get **port conflicts**.

When to use **`aeron-media-driver`**:

- You run **only** Aeron clients inside Docker and want a **standalone** driver in the stack, **and** you are **not** binding the same UDP ports on the host from the aggregator, **or**
- You are experimenting with a **custom** deployment where the aggregator does not embed the driver (would require code/configuration changes not in the default `main` flow).

Start optional Aeron driver:

```bash
docker compose --profile aeron up -d aeron-media-driver
```

---

## 3. Build the application

```bash
mvn clean package -DskipTests
```

Fat JAR:

```bash
target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar
```

---

## 4. Run the application

With Kafka already up and defaults in `src/main/resources/application.properties`:

```bash
java -jar target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar
```

### Environment variable overrides

`AppConfig` maps environment variables by taking the property key, replacing `.` with `_`, and uppercasing. Examples:

| Property | Environment variable |
|----------|-------------------------|
| `kafka.bootstrap.servers` | `KAFKA_BOOTSTRAP_SERVERS` |
| `kafka.consumer.topic` | `KAFKA_CONSUMER_TOPIC` |
| `kafka.producer.topic` | `KAFKA_PRODUCER_TOPIC` |
| `aeron.subscriber.channel` | `AERON_SUBSCRIBER_CHANNEL` |
| `chronicle.map.file` | `CHRONICLE_MAP_FILE` |

Example:

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
java -jar target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar
```

### Chronicle Map file

By default the map file is under the system temp directory (see `AppConfig.chronicleMapFile()` when `chronicle.map.file` is unset). For a clean run, point it to a dedicated path:

```bash
export CHRONICLE_MAP_FILE=/tmp/volume-aggregator-dev.dat
java -jar target/trade-volume-aggregator-1.0.0-SNAPSHOT.jar
```

---

## 5. Verify Kafka (quick checks)

**Consumer on snapshots** (in another terminal):

```bash
docker exec -it trade-volume-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic volume-snapshots \
  --from-beginning
```

**Producer sample trade** (JSON matches `TradeEvent` in code):

```bash
echo '{"userId":1,"symbol":"BTCUSDT","volume":0.5,"timestampMs":1730000000000}' | \
  docker exec -i trade-volume-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic trade-events \
  --property "parse.key=false"
```

---

## 6. Verify Aeron (high level)

There is **no** one-line “official” CLI in this repo for UDP binary frames. In practice you either:

- Use a **separate** small Aeron publisher/subscriber that encodes the same layout as documented in `README.md` (**Trade event** and **Volume snapshot** tables), or
- Use integration tests / internal tools.

Ensure any external publisher targets **`localhost:40123`** (UDP) and stream ID **1001** if you keep default `application.properties`.

---

## 7. Troubleshooting

| Symptom | Things to check |
|---------|------------------|
| Cannot connect to Kafka | `docker compose ps`; broker on `localhost:9092`; `KAFKA_BOOTSTRAP_SERVERS`. |
| Topics missing | Re-run `docker compose run --rm kafka-init` or create topics manually with `kafka-topics.sh`. |
| UDP / Aeron issues | Embedded driver vs optional `aeron-media-driver` **port clash**; firewall; correct `aeron.*` URIs and stream IDs. |
| Chronicle / reflection errors | Run the JAR with appropriate `--add-opens` / `--add-exports` (see Prerequisites). |

---

## File reference

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Kafka, topic init, **Kafka UI** on port 8080, optional `aeron-media-driver` profile. |
| `docker/aeron-media-driver/Dockerfile` | Builds standalone Aeron Media Driver JAR from Maven Central. |
| `src/main/resources/application.properties` | Default broker, topics, Aeron URIs, snapshot interval. |
| `README.md` | Architecture, wire formats, configuration table. |
