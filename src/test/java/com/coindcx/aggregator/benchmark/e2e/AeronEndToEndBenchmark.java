package com.coindcx.aggregator.benchmark.e2e;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.consumer.AeronTradeSubscriber;
import com.coindcx.aggregator.publisher.AeronSnapshotPublisher;
import com.coindcx.aggregator.publisher.KafkaSnapshotPublisher;
import com.coindcx.aggregator.snapshot.SnapshotEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end benchmark for the Aeron pipeline path with rate-controlled
 * latency measurement at multiple TPS levels.
 *
 * Fully self-contained — no external infrastructure required.
 * Uses IPC channels within an embedded MediaDriver.
 */
public class AeronEndToEndBenchmark {

    private static final Logger LOG = LoggerFactory.getLogger(AeronEndToEndBenchmark.class);

    private static final long SNAPSHOT_INTERVAL_MS = 200;
    private static final int WARMUP_EVENTS = 500;
    private static final int THROUGHPUT_EVENTS = 50_000;
    private static final int LOAD_USER_CARDINALITY = 1_000;

    private static final int[] TARGET_TPS = {1_000, 5_000, 10_000, 50_000, 100_000};
    private static final int PROBES_PER_LEVEL = 200;
    private static final int DURATION_PER_LEVEL_S = 5;

    private static final int TRADE_STREAM = 3001;
    private static final int SNAPSHOT_STREAM = 3002;
    private static final byte[] SYMBOL_BYTES = "BTC/USDT".getBytes(StandardCharsets.UTF_8);
    private static final int TRADE_MSG_LEN = 28 + SYMBOL_BYTES.length;

    public static void main(String[] args) throws Exception {
        new AeronEndToEndBenchmark().run();
    }

    @SuppressWarnings("unchecked")
    private void run() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append(header());

        File mapFile = File.createTempFile("aeron-e2e-bench-", ".dat");
        mapFile.deleteOnExit();
        String aeronDirPath = System.getProperty("java.io.tmpdir") + "/aeron-e2e-bench-" + System.nanoTime();

        Properties overrides = new Properties();
        overrides.setProperty("aeron.subscriber.channel", "aeron:ipc");
        overrides.setProperty("aeron.subscriber.stream.id", String.valueOf(TRADE_STREAM));
        overrides.setProperty("aeron.publisher.channel", "aeron:ipc");
        overrides.setProperty("aeron.publisher.stream.id", String.valueOf(SNAPSHOT_STREAM));
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
        AeronTradeSubscriber subscriber = new AeronTradeSubscriber(aeron, config, aggregator);
        AeronSnapshotPublisher aeronPublisher = new AeronSnapshotPublisher(aeron, config);

        KafkaProducer<String, String> mockProducer = Mockito.mock(KafkaProducer.class);
        KafkaSnapshotPublisher kafkaPublisher =
                new KafkaSnapshotPublisher(mockProducer, "unused", new ObjectMapper());

        SnapshotEmitter emitter = new SnapshotEmitter(config, aggregator, kafkaPublisher, aeronPublisher);

        Thread subThread = new Thread(subscriber, "bench-aeron-subscriber");
        subThread.setDaemon(true);
        subThread.start();
        emitter.start();

        Publication testPub = aeron.addPublication("aeron:ipc", TRADE_STREAM);
        Subscription testSub = aeron.addSubscription("aeron:ipc", SNAPSHOT_STREAM);
        while (!testPub.isConnected()) Thread.onSpinWait();
        Thread.sleep(500);

        // Phase 1: Warmup
        LOG.info("Phase 1/2: Warmup ({} events)", WARMUP_EVENTS);
        sendBurst(testPub, 1, WARMUP_EVENTS, 100);
        Thread.sleep(SNAPSHOT_INTERVAL_MS * 3);
        drain(testSub);

        // Phase 2: Rate-controlled latency at each TPS level
        LOG.info("Phase 2/2: Rate-controlled latency ({} TPS levels)", TARGET_TPS.length);
        report.append(measureRateControlledLatency(testPub, testSub));

        report.append(footer());
        String fullReport = report.toString();
        System.out.println(fullReport);

        File reportFile = new File("target/aeron-e2e-report.txt");
        try (PrintWriter pw = new PrintWriter(reportFile)) { pw.print(fullReport); }
        LOG.info("Report saved to {}", reportFile.getAbsolutePath());

        emitter.close();
        subscriber.close();
        subThread.join(3000);
        testPub.close();
        testSub.close();
        aggregator.close();
        aeron.close();
        driver.close();
        mapFile.delete();
    }

    // -----------------------------------------------------------------------
    //  Rate-controlled latency
    // -----------------------------------------------------------------------

    private String measureRateControlledLatency(Publication pub, Subscription sub)
            throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "--- Rate-Controlled Latency: Aeron send → snapshot received ---%n" +
                "  Snapshot interval : %d ms%n" +
                "  Probes per level  : %d%n" +
                "  Duration per level: %d s%n%n", SNAPSHOT_INTERVAL_MS, PROBES_PER_LEVEL, DURATION_PER_LEVEL_S));

        sb.append(String.format("  %-10s | %-7s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                "Target TPS", "Samples", "p50", "p90", "p95", "p99", "max"));
        sb.append(String.format("  %s%n", "-".repeat(79)));

        long probeBaseUserId = 5_000_000;

        for (int targetTps : TARGET_TPS) {
            LOG.info("  Running {} TPS ...", targetTps);
            drain(sub);
            Thread.sleep(SNAPSHOT_INTERVAL_MS * 2);
            drain(sub);

            LatencyRecorder recorder = new LatencyRecorder(PROBES_PER_LEVEL);
            ConcurrentHashMap<Long, Long> probeSendTimes = new ConcurrentHashMap<>();
            AtomicBoolean receiverRunning = new AtomicBoolean(true);
            AtomicInteger probesReceived = new AtomicInteger(0);

            Thread receiver = new Thread(() -> {
                while (receiverRunning.get()) {
                    sub.poll((buf, offset, length, header) -> {
                        long snapUserId = buf.getLong(offset);
                        Long sendNs = probeSendTimes.remove(snapUserId);
                        if (sendNs != null) {
                            recorder.record(System.nanoTime() - sendNs);
                            probesReceived.incrementAndGet();
                        }
                    }, 256);
                }
            }, "bench-receiver");
            receiver.setDaemon(true);
            receiver.start();

            long intervalNs = 1_000_000_000L / targetTps;
            int totalEvents = targetTps * DURATION_PER_LEVEL_S;
            int probeEvery = Math.max(1, totalEvents / PROBES_PER_LEVEL);
            int probeCount = 0;

            UnsafeBuffer buf = new UnsafeBuffer(BufferUtil.allocateDirectAligned(256, 64));
            long nextSendNs = System.nanoTime();

            for (int i = 0; i < totalEvents && probeCount <= PROBES_PER_LEVEL; i++) {
                while (System.nanoTime() < nextSendNs) Thread.onSpinWait();

                long userId;
                if (i % probeEvery == 0 && probeCount < PROBES_PER_LEVEL) {
                    userId = probeBaseUserId + probeCount;
                    probeSendTimes.put(userId, System.nanoTime());
                    probeCount++;
                } else {
                    userId = 1 + (i % LOAD_USER_CARDINALITY);
                }

                encodeTrade(buf, userId, 1.0, System.currentTimeMillis());
                long offerResult;
                do {
                    offerResult = pub.offer(buf, 0, TRADE_MSG_LEN);
                } while (offerResult < 0);

                nextSendNs += intervalNs;
            }

            long drainDeadline = System.nanoTime() + SNAPSHOT_INTERVAL_MS * 5L * 1_000_000L;
            while (probesReceived.get() < probeCount && System.nanoTime() < drainDeadline) {
                Thread.sleep(20);
            }

            receiverRunning.set(false);
            receiver.join(1000);

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

    private static void encodeTrade(UnsafeBuffer buf, long userId, double volume, long timestampMs) {
        buf.putLong(0, userId);
        buf.putDouble(8, volume);
        buf.putLong(16, timestampMs);
        buf.putInt(24, SYMBOL_BYTES.length);
        buf.putBytes(28, SYMBOL_BYTES);
    }

    private void sendBurst(Publication pub, long baseUserId, int count, int userCardinality) {
        UnsafeBuffer buf = new UnsafeBuffer(BufferUtil.allocateDirectAligned(256, 64));
        for (int i = 0; i < count; i++) {
            long userId = baseUserId + (i % userCardinality);
            encodeTrade(buf, userId, 1.0, System.currentTimeMillis());
            while (pub.offer(buf, 0, TRADE_MSG_LEN) < 0) Thread.onSpinWait();
        }
    }

    private static void drain(Subscription sub) {
        int drained;
        do { drained = sub.poll((b, o, l, h) -> { }, 100); } while (drained > 0);
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
                "         AERON END-TO-END BENCHMARK REPORT%n" +
                "============================================================%n" +
                "  Date      : %s%n" +
                "  Transport : Aeron IPC (embedded MediaDriver)%n" +
                "  Pipeline  : Publication → Subscriber → Aggregator%n" +
                "              → Emitter (%dms) → Publisher → Subscription%n" +
                "  JDK       : %s%n" +
                "============================================================%n%n",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                SNAPSHOT_INTERVAL_MS, System.getProperty("java.version"));
    }

    private static String footer() {
        return String.format(
                "============================================================%n" +
                "  NOTES%n" +
                "  - Round-trip latency includes the %dms snapshot interval.%n" +
                "  - Latency growth at higher TPS indicates pipeline%n" +
                "    saturation (snapshot emission overhead, contention).%n" +
                "  - Background load uses %,d user cardinality; probes use%n" +
                "    unique IDs so each is measured independently.%n" +
                "============================================================%n",
                SNAPSHOT_INTERVAL_MS, LOAD_USER_CARDINALITY);
    }
}
