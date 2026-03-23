package com.coindcx.aggregator.benchmark.e2e;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures ingestion latency for the Kafka path:
 * producer.send() → broker → consumer.poll() → JSON decode → addTrade() → Chronicle Map updated.
 *
 * No SnapshotEmitter involved — purely transport + deserialization + off-heap write.
 * Requires: docker compose up -d kafka kafka-init
 */
public class KafkaIngestionBenchmark {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaIngestionBenchmark.class);

    private static final String BOOTSTRAP = "localhost:9092";
    private static final String TRADE_TOPIC = "bench-ingestion-trades";

    private static final int WARMUP_EVENTS = 1_000;
    private static final int LOAD_USER_CARDINALITY = 1_000;

    private static final int[] TARGET_TPS = {1_000, 5_000, 10_000, 50_000, 100_000};
    private static final int PROBES_PER_LEVEL = 500;
    private static final int DURATION_PER_LEVEL_S = 5;

    public static void main(String[] args) throws Exception {
        new KafkaIngestionBenchmark().run();
    }

    private void run() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append(header());

        File mapFile = File.createTempFile("kafka-ingest-bench-", ".dat");
        mapFile.deleteOnExit();

        Properties overrides = new Properties();
        overrides.setProperty("kafka.bootstrap.servers", BOOTSTRAP);
        overrides.setProperty("kafka.consumer.topic", TRADE_TOPIC);
        overrides.setProperty("kafka.producer.topic", "unused");
        overrides.setProperty("kafka.group.id", "bench-ingest-" + System.currentTimeMillis());
        overrides.setProperty("snapshot.interval.ms", "60000");
        overrides.setProperty("chronicle.map.entries", "200000");
        overrides.setProperty("chronicle.map.file", mapFile.getAbsolutePath());
        overrides.setProperty("aeron.directory",
                System.getProperty("java.io.tmpdir") + "/aeron-kafka-ingest-" + System.nanoTime());
        AppConfig config = new AppConfig(overrides);

        VolumeAggregator aggregator = new VolumeAggregator(config);
        ObjectMapper mapper = new ObjectMapper();

        KafkaConsumer<String, String> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(TRADE_TOPIC));
        consumer.poll(Duration.ofSeconds(2));

        KafkaProducer<String, String> producer = createProducer();

        // Shared probe tracking per level (replaced for each TPS level)
        final ConcurrentHashMap<Long, Long>[] probeMap = new ConcurrentHashMap[]{new ConcurrentHashMap<>()};
        final LatencyRecorder[] recorderRef = new LatencyRecorder[]{new LatencyRecorder(PROBES_PER_LEVEL)};
        final AtomicInteger[] completedRef = new AtomicInteger[]{new AtomicInteger(0)};

        AtomicBoolean consumerRunning = new AtomicBoolean(true);
        Thread consumerThread = new Thread(() -> {
            while (consumerRunning.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        TradeEvent event = mapper.readValue(record.value(), TradeEvent.class);
                        aggregator.addTrade(event);

                        Long sendNs = probeMap[0].remove(event.getUserId());
                        if (sendNs != null) {
                            recorderRef[0].record(System.nanoTime() - sendNs);
                            completedRef[0].incrementAndGet();
                        }
                    } catch (Exception e) {
                        LOG.warn("Decode error: {}", e.getMessage());
                    }
                }
            }
        }, "bench-kafka-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        Thread.sleep(2000);

        // Warmup
        LOG.info("Warmup: {} events", WARMUP_EVENTS);
        sendKafkaBurst(producer, 1, WARMUP_EVENTS);
        Thread.sleep(2000);

        LOG.info("Running {} TPS levels, {} probes each", TARGET_TPS.length, PROBES_PER_LEVEL);

        report.append(String.format(
                "--- Ingestion Latency: send() → broker → poll() → addTrade() → Chronicle Map ---%n" +
                "  No snapshot emitter — pure Kafka transit + JSON decode + off-heap write.%n" +
                "  Consumer poll timeout: 10ms%n%n"));
        report.append(String.format("  %-10s | %-7s | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                "Target TPS", "Samples", "p50", "p90", "p95", "p99", "p99.9", "max"));
        report.append(String.format("  %s%n", "-".repeat(89)));

        long probeBase = 5_000_000;

        for (int targetTps : TARGET_TPS) {
            LOG.info("  {} TPS ...", targetTps);
            Thread.sleep(500);

            LatencyRecorder levelRecorder = new LatencyRecorder(PROBES_PER_LEVEL);
            ConcurrentHashMap<Long, Long> levelProbes = new ConcurrentHashMap<>();
            AtomicInteger levelCompleted = new AtomicInteger(0);

            recorderRef[0] = levelRecorder;
            probeMap[0] = levelProbes;
            completedRef[0] = levelCompleted;

            long intervalNs = 1_000_000_000L / targetTps;
            int totalEvents = targetTps * DURATION_PER_LEVEL_S;
            int probeEvery = Math.max(1, totalEvents / PROBES_PER_LEVEL);
            int probeCount = 0;

            long nextSendNs = System.nanoTime();

            for (int i = 0; i < totalEvents && probeCount <= PROBES_PER_LEVEL; i++) {
                while (System.nanoTime() < nextSendNs) Thread.onSpinWait();

                long userId;
                boolean isProbe = (i % probeEvery == 0 && probeCount < PROBES_PER_LEVEL);
                if (isProbe) {
                    userId = probeBase + probeCount;
                    probeCount++;
                } else {
                    userId = 1 + (i % LOAD_USER_CARDINALITY);
                }

                String json = String.format(
                        "{\"userId\":%d,\"symbol\":\"BTC/USDT\",\"volume\":1.0,\"timestampMs\":%d}",
                        userId, System.currentTimeMillis());

                if (isProbe) {
                    levelProbes.put(userId, System.nanoTime());
                }

                producer.send(new ProducerRecord<>(TRADE_TOPIC, String.valueOf(userId), json));

                if (i % 200 == 199) producer.flush();

                nextSendNs += intervalNs;
            }
            producer.flush();

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (levelCompleted.get() < probeCount && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            probeBase += PROBES_PER_LEVEL;

            LatencyRecorder.LatencyStats s = levelRecorder.compute();
            report.append(String.format("  %-10s | %-7d | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                    fmtTps(targetTps), s.sampleCount(),
                    fmtLat(s.p50Ns()), fmtLat(s.p90Ns()), fmtLat(s.p95Ns()),
                    fmtLat(s.p99Ns()), fmtLat(s.p999Ns()), fmtLat(s.maxNs())));
        }

        consumerRunning.set(false);
        consumerThread.join(2000);

        report.append(String.format("%n"));
        report.append(footer());

        String fullReport = report.toString();
        System.out.println(fullReport);

        File reportFile = new File("target/kafka-ingestion-report.txt");
        try (PrintWriter pw = new PrintWriter(reportFile)) { pw.print(fullReport); }
        LOG.info("Report saved to {}", reportFile.getAbsolutePath());

        producer.close();
        consumer.close();
        aggregator.close();
        mapFile.delete();
    }

    private void sendKafkaBurst(KafkaProducer<String, String> producer, long baseUserId, int count) {
        for (int i = 0; i < count; i++) {
            long userId = baseUserId + (i % LOAD_USER_CARDINALITY);
            String json = String.format(
                    "{\"userId\":%d,\"symbol\":\"BTC/USDT\",\"volume\":1.0,\"timestampMs\":%d}",
                    userId, System.currentTimeMillis());
            producer.send(new ProducerRecord<>(TRADE_TOPIC, String.valueOf(userId), json));
        }
        producer.flush();
    }

    private static KafkaProducer<String, String> createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "1");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<String, String> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "bench-ingest-consumer-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1");
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "10");
        return new KafkaConsumer<>(props);
    }

    private static String fmtTps(int tps) {
        return tps >= 1000 ? String.format("%,dK", tps / 1000) : String.valueOf(tps);
    }

    private static String fmtLat(long nanos) {
        if (nanos < 1_000) return nanos + " ns";
        if (nanos < 1_000_000) return String.format("%.2f µs", nanos / 1_000.0);
        return String.format("%.2f ms", nanos / 1_000_000.0);
    }

    private static String header() {
        return String.format(
                "============================================================%n" +
                "     KAFKA INGESTION LATENCY BENCHMARK%n" +
                "============================================================%n" +
                "  Date      : %s%n" +
                "  Broker    : %s%n" +
                "  Topic     : %s%n" +
                "  Measured  : producer.send() → broker → consumer.poll()%n" +
                "              → JSON deserialize → addTrade()%n" +
                "              → Chronicle Map compute() returns%n" +
                "  JDK       : %s%n" +
                "============================================================%n%n",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                BOOTSTRAP, TRADE_TOPIC, System.getProperty("java.version"));
    }

    private static String footer() {
        return String.format(
                "============================================================%n" +
                "  NOTES%n" +
                "  - Measures full Kafka ingestion: producer → broker →%n" +
                "    consumer poll → JSON decode → Chronicle Map write.%n" +
                "  - No snapshot emission involved.%n" +
                "  - Consumer uses fetch.max.wait.ms=10 and poll(10ms)%n" +
                "    to minimise consumer-side batching delay.%n" +
                "  - Background load uses %,d user cardinality; probes%n" +
                "    use unique IDs measured independently.%n" +
                "============================================================%n",
                LOAD_USER_CARDINALITY);
    }
}
