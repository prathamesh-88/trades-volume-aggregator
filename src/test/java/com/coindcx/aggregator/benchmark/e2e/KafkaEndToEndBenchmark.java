package com.coindcx.aggregator.benchmark.e2e;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.consumer.KafkaTradeConsumer;
import com.coindcx.aggregator.publisher.AeronSnapshotPublisher;
import com.coindcx.aggregator.publisher.KafkaSnapshotPublisher;
import com.coindcx.aggregator.snapshot.SnapshotEmitter;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
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
 * End-to-end benchmark for the Kafka pipeline path with rate-controlled
 * latency measurement at multiple TPS levels.
 *
 * Requires: docker compose up -d kafka kafka-init
 */
public class KafkaEndToEndBenchmark {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEndToEndBenchmark.class);

    private static final String BOOTSTRAP = "localhost:9092";
    private static final String TRADE_TOPIC = "bench-trade-events";
    private static final String SNAPSHOT_TOPIC = "bench-volume-snapshots";

    private static final long SNAPSHOT_INTERVAL_MS = 200;
    private static final int WARMUP_EVENTS = 500;
    private static final int LOAD_USER_CARDINALITY = 1_000;

    private static final int[] TARGET_TPS = {1_000, 5_000, 10_000, 50_000, 100_000};
    private static final int PROBES_PER_LEVEL = 150;
    private static final int DURATION_PER_LEVEL_S = 5;

    public static void main(String[] args) throws Exception {
        new KafkaEndToEndBenchmark().run();
    }

    private void run() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append(header());

        File mapFile = File.createTempFile("kafka-e2e-bench-", ".dat");
        mapFile.deleteOnExit();
        String aeronDirPath = System.getProperty("java.io.tmpdir") + "/aeron-kafka-e2e-" + System.nanoTime();

        Properties overrides = new Properties();
        overrides.setProperty("kafka.bootstrap.servers", BOOTSTRAP);
        overrides.setProperty("kafka.consumer.topic", TRADE_TOPIC);
        overrides.setProperty("kafka.producer.topic", SNAPSHOT_TOPIC);
        overrides.setProperty("kafka.group.id", "bench-aggregator-" + System.currentTimeMillis());
        overrides.setProperty("aeron.subscriber.channel", "aeron:ipc");
        overrides.setProperty("aeron.subscriber.stream.id", "4001");
        overrides.setProperty("aeron.publisher.channel", "aeron:ipc");
        overrides.setProperty("aeron.publisher.stream.id", "4002");
        overrides.setProperty("snapshot.interval.ms", String.valueOf(SNAPSHOT_INTERVAL_MS));
        overrides.setProperty("chronicle.map.entries", "200000");
        overrides.setProperty("chronicle.map.file", mapFile.getAbsolutePath());
        overrides.setProperty("aeron.directory", aeronDirPath);
        AppConfig config = new AppConfig(overrides);

        MediaDriver driver = MediaDriver.launchEmbedded(
                new MediaDriver.Context().aeronDirectoryName(config.aeronDirectory()));
        Aeron aeron = Aeron.connect(
                new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));

        VolumeAggregator aggregator = new VolumeAggregator(config);
        KafkaTradeConsumer tradeConsumer = new KafkaTradeConsumer(config, aggregator);
        KafkaSnapshotPublisher kafkaPublisher = new KafkaSnapshotPublisher(config);
        AeronSnapshotPublisher aeronPublisher = new AeronSnapshotPublisher(aeron, config);
        SnapshotEmitter emitter = new SnapshotEmitter(config, aggregator, kafkaPublisher, aeronPublisher);

        Thread consumerThread = new Thread(tradeConsumer, "bench-kafka-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        emitter.start();

        KafkaProducer<String, String> testProducer = createTestProducer();
        KafkaConsumer<String, String> testSnapshotConsumer = createTestSnapshotConsumer();
        testSnapshotConsumer.subscribe(Collections.singletonList(SNAPSHOT_TOPIC));
        testSnapshotConsumer.poll(Duration.ofSeconds(2));
        Thread.sleep(2000);

        // Phase 1: Warmup
        LOG.info("Phase 1/2: Warmup ({} events)", WARMUP_EVENTS);
        sendKafkaBurst(testProducer, 1, WARMUP_EVENTS, 100);
        Thread.sleep(SNAPSHOT_INTERVAL_MS * 5);
        drainKafkaConsumer(testSnapshotConsumer);

        // Phase 2: Rate-controlled latency
        LOG.info("Phase 2/2: Rate-controlled latency ({} TPS levels)", TARGET_TPS.length);
        report.append(measureRateControlledLatency(testProducer, testSnapshotConsumer));

        report.append(footer());
        String fullReport = report.toString();
        System.out.println(fullReport);

        File reportFile = new File("target/kafka-e2e-report.txt");
        try (PrintWriter pw = new PrintWriter(reportFile)) { pw.print(fullReport); }
        LOG.info("Report saved to {}", reportFile.getAbsolutePath());

        emitter.close();
        tradeConsumer.close();
        consumerThread.join(5000);
        testProducer.close();
        testSnapshotConsumer.close();
        kafkaPublisher.close();
        aeronPublisher.close();
        aggregator.close();
        aeron.close();
        driver.close();
        mapFile.delete();
    }

    // -----------------------------------------------------------------------
    //  Rate-controlled latency
    // -----------------------------------------------------------------------

    private String measureRateControlledLatency(KafkaProducer<String, String> producer,
                                                 KafkaConsumer<String, String> snapshotConsumer)
            throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "--- Rate-Controlled Latency: Kafka produce → snapshot consume ---%n" +
                "  Snapshot interval : %d ms%n" +
                "  Probes per level  : %d%n" +
                "  Duration per level: %d s%n%n", SNAPSHOT_INTERVAL_MS, PROBES_PER_LEVEL, DURATION_PER_LEVEL_S));

        sb.append(String.format("  %-10s | %-7s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                "Target TPS", "Samples", "p50", "p90", "p95", "p99", "max"));
        sb.append(String.format("  %s%n", "-".repeat(79)));

        long probeBaseUserId = 5_000_000;

        for (int targetTps : TARGET_TPS) {
            LOG.info("  Running {} TPS ...", targetTps);
            Thread.sleep(SNAPSHOT_INTERVAL_MS * 2);
            drainKafkaConsumer(snapshotConsumer);

            LatencyRecorder recorder = new LatencyRecorder(PROBES_PER_LEVEL);
            ConcurrentHashMap<String, Long> probeSendTimes = new ConcurrentHashMap<>();
            AtomicBoolean receiverRunning = new AtomicBoolean(true);
            AtomicInteger probesReceived = new AtomicInteger(0);

            Thread receiver = new Thread(() -> {
                while (receiverRunning.get()) {
                    ConsumerRecords<String, String> records =
                            snapshotConsumer.poll(Duration.ofMillis(50));
                    for (ConsumerRecord<String, String> record : records) {
                        Long sendNs = probeSendTimes.remove(record.key());
                        if (sendNs != null) {
                            recorder.record(System.nanoTime() - sendNs);
                            probesReceived.incrementAndGet();
                        }
                    }
                }
            }, "bench-kafka-receiver");
            receiver.setDaemon(true);
            receiver.start();

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
                    userId = probeBaseUserId + probeCount;
                    probeCount++;
                } else {
                    userId = 1 + (i % LOAD_USER_CARDINALITY);
                }

                String key = String.valueOf(userId);
                String json = String.format(
                        "{\"userId\":%d,\"symbol\":\"BTC/USDT\",\"volume\":1.0,\"timestampMs\":%d}",
                        userId, System.currentTimeMillis());

                if (isProbe) {
                    probeSendTimes.put(key, System.nanoTime());
                }

                producer.send(new ProducerRecord<>(TRADE_TOPIC, key, json));

                if (i % 500 == 499) producer.flush();

                nextSendNs += intervalNs;
            }
            producer.flush();

            long drainDeadline = System.nanoTime() + SNAPSHOT_INTERVAL_MS * 10L * 1_000_000L;
            while (probesReceived.get() < probeCount && System.nanoTime() < drainDeadline) {
                Thread.sleep(50);
            }

            receiverRunning.set(false);
            receiver.join(2000);

            probeBaseUserId += PROBES_PER_LEVEL;

            LatencyRecorder.LatencyStats s = recorder.compute();
            sb.append(String.format("  %-10s | %-7d | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                    formatTps(targetTps), s.sampleCount(),
                    fmtMs(s.p50Ns()), fmtMs(s.p90Ns()), fmtMs(s.p95Ns()),
                    fmtMs(s.p99Ns()), fmtMs(s.maxNs())));
        }

        sb.append(String.format("%n"));
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void sendKafkaBurst(KafkaProducer<String, String> producer,
                                long baseUserId, int count, int userCardinality) {
        for (int i = 0; i < count; i++) {
            long userId = baseUserId + (i % userCardinality);
            String json = String.format(
                    "{\"userId\":%d,\"symbol\":\"BTC/USDT\",\"volume\":1.0,\"timestampMs\":%d}",
                    userId, System.currentTimeMillis());
            producer.send(new ProducerRecord<>(TRADE_TOPIC, String.valueOf(userId), json));
        }
        producer.flush();
    }

    private static KafkaProducer<String, String> createTestProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<String, String> createTestSnapshotConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "bench-snapshot-reader-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return new KafkaConsumer<>(props);
    }

    private static void drainKafkaConsumer(KafkaConsumer<String, String> consumer) {
        ConsumerRecords<String, String> records;
        do { records = consumer.poll(Duration.ofMillis(200)); } while (!records.isEmpty());
    }

    private static String formatTps(int tps) {
        return tps >= 1000 ? String.format("%,dK", tps / 1000) : String.valueOf(tps);
    }

    private static String fmtMs(long nanos) {
        return String.format("%.2f ms", nanos / 1_000_000.0);
    }

    private static String header() {
        return String.format(
                "============================================================%n" +
                "         KAFKA END-TO-END BENCHMARK REPORT%n" +
                "============================================================%n" +
                "  Date      : %s%n" +
                "  Broker    : %s%n" +
                "  Topics    : %s → %s%n" +
                "  Pipeline  : Producer → Broker → Consumer → Aggregator%n" +
                "              → Emitter (%dms) → Publisher → Broker → Consumer%n" +
                "  JDK       : %s%n" +
                "============================================================%n%n",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                BOOTSTRAP, TRADE_TOPIC, SNAPSHOT_TOPIC,
                SNAPSHOT_INTERVAL_MS, System.getProperty("java.version"));
    }

    private static String footer() {
        return String.format(
                "============================================================%n" +
                "  NOTES%n" +
                "  - Round-trip latency includes the %dms snapshot interval%n" +
                "    plus Kafka broker transit (produce + consume) on both ends.%n" +
                "  - Latency growth at higher TPS indicates pipeline%n" +
                "    saturation or Kafka broker contention.%n" +
                "  - Background load uses %,d user cardinality; probes use%n" +
                "    unique IDs so each is measured independently.%n" +
                "============================================================%n",
                SNAPSHOT_INTERVAL_MS, LOAD_USER_CARDINALITY);
    }
}
