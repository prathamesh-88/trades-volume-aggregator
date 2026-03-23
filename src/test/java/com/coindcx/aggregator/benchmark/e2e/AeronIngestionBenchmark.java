package com.coindcx.aggregator.benchmark.e2e;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
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
 * Measures ingestion latency for the Aeron path:
 * Publication.offer() → AeronSubscriber decode → aggregator.addTrade() → Chronicle Map updated.
 *
 * No SnapshotEmitter involved — purely transport + deserialization + off-heap write.
 */
public class AeronIngestionBenchmark {

    private static final Logger LOG = LoggerFactory.getLogger(AeronIngestionBenchmark.class);

    private static final int TRADE_STREAM = 5001;
    private static final int WARMUP_EVENTS = 1_000;
    private static final int LOAD_USER_CARDINALITY = 1_000;

    private static final int[] TARGET_TPS = {1_000, 5_000, 10_000, 50_000, 100_000};
    private static final int PROBES_PER_LEVEL = 500;
    private static final int DURATION_PER_LEVEL_S = 5;

    private static final byte[] SYMBOL_BYTES = "BTC/USDT".getBytes(StandardCharsets.UTF_8);
    private static final int TRADE_MSG_LEN = 28 + SYMBOL_BYTES.length;

    public static void main(String[] args) throws Exception {
        new AeronIngestionBenchmark().run();
    }

    private void run() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append(header());

        File mapFile = File.createTempFile("aeron-ingest-bench-", ".dat");
        mapFile.deleteOnExit();
        String aeronDir = System.getProperty("java.io.tmpdir") + "/aeron-ingest-bench-" + System.nanoTime();

        Properties overrides = new Properties();
        overrides.setProperty("aeron.subscriber.channel", "aeron:ipc");
        overrides.setProperty("aeron.subscriber.stream.id", String.valueOf(TRADE_STREAM));
        overrides.setProperty("aeron.publisher.channel", "aeron:ipc");
        overrides.setProperty("aeron.publisher.stream.id", "9999");
        overrides.setProperty("snapshot.interval.ms", "60000");
        overrides.setProperty("chronicle.map.entries", "200000");
        overrides.setProperty("chronicle.map.file", mapFile.getAbsolutePath());
        overrides.setProperty("aeron.directory", aeronDir);
        AppConfig config = new AppConfig(overrides);

        MediaDriver driver = MediaDriver.launchEmbedded(
                new MediaDriver.Context().aeronDirectoryName(config.aeronDirectory()));
        Aeron aeron = Aeron.connect(
                new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));

        VolumeAggregator aggregator = new VolumeAggregator(config);

        ConcurrentHashMap<Long, Long> probeSendTimes = new ConcurrentHashMap<>();
        LatencyRecorder sharedRecorder = new LatencyRecorder(PROBES_PER_LEVEL * TARGET_TPS.length);
        AtomicInteger probesCompleted = new AtomicInteger(0);
        AtomicBoolean consumerRunning = new AtomicBoolean(true);

        Subscription sub = aeron.addSubscription("aeron:ipc", TRADE_STREAM);
        Thread consumerThread = new Thread(() -> {
            while (consumerRunning.get()) {
                sub.poll((buffer, offset, length, hdr) -> {
                    long userId = buffer.getLong(offset);
                    double volume = buffer.getDouble(offset + 8);
                    long timestampMs = buffer.getLong(offset + 16);
                    int symbolLen = buffer.getInt(offset + 24);
                    byte[] symBytes = new byte[symbolLen];
                    buffer.getBytes(offset + 28, symBytes);

                    TradeEvent event = new TradeEvent(
                            userId, new String(symBytes, StandardCharsets.UTF_8), volume, timestampMs);
                    aggregator.addTrade(event);

                    Long sendNs = probeSendTimes.remove(userId);
                    if (sendNs != null) {
                        sharedRecorder.record(System.nanoTime() - sendNs);
                        probesCompleted.incrementAndGet();
                    }
                }, 64);
            }
        }, "bench-aeron-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        Publication pub = aeron.addPublication("aeron:ipc", TRADE_STREAM);
        while (!pub.isConnected()) Thread.onSpinWait();
        Thread.sleep(300);

        // Warmup
        LOG.info("Warmup: {} events", WARMUP_EVENTS);
        sendBurst(pub, 1, WARMUP_EVENTS);
        Thread.sleep(500);

        // Rate-controlled ingestion latency at each TPS level
        LOG.info("Running {} TPS levels, {} probes each", TARGET_TPS.length, PROBES_PER_LEVEL);

        report.append(String.format(
                "--- Ingestion Latency: offer() → addTrade() → Chronicle Map updated ---%n" +
                "  No snapshot emitter — pure transport + decode + off-heap write.%n%n"));
        report.append(String.format("  %-10s | %-7s | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                "Target TPS", "Samples", "p50", "p90", "p95", "p99", "p99.9", "max"));
        report.append(String.format("  %s%n", "-".repeat(89)));

        long probeBase = 5_000_000;

        for (int targetTps : TARGET_TPS) {
            LOG.info("  {} TPS ...", targetTps);

            LatencyRecorder levelRecorder = new LatencyRecorder(PROBES_PER_LEVEL);
            ConcurrentHashMap<Long, Long> levelProbes = new ConcurrentHashMap<>();
            AtomicInteger levelCompleted = new AtomicInteger(0);
            AtomicBoolean levelConsumerRunning = new AtomicBoolean(true);

            Subscription levelSub = aeron.addSubscription("aeron:ipc", TRADE_STREAM);
            Thread levelConsumer = new Thread(() -> {
                while (levelConsumerRunning.get()) {
                    levelSub.poll((buffer, offset, length, hdr) -> {
                        long userId = buffer.getLong(offset);
                        double volume = buffer.getDouble(offset + 8);
                        long timestampMs = buffer.getLong(offset + 16);
                        int symbolLen = buffer.getInt(offset + 24);
                        byte[] symBytes = new byte[symbolLen];
                        buffer.getBytes(offset + 28, symBytes);

                        TradeEvent event = new TradeEvent(
                                userId, new String(symBytes, StandardCharsets.UTF_8), volume, timestampMs);
                        aggregator.addTrade(event);

                        Long sendNs = levelProbes.remove(userId);
                        if (sendNs != null) {
                            levelRecorder.record(System.nanoTime() - sendNs);
                            levelCompleted.incrementAndGet();
                        }
                    }, 64);
                }
            }, "bench-consumer-" + targetTps);
            levelConsumer.setDaemon(true);
            levelConsumer.start();
            Thread.sleep(100);

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
                    userId = probeBase + probeCount;
                    levelProbes.put(userId, System.nanoTime());
                    probeCount++;
                } else {
                    userId = 1 + (i % LOAD_USER_CARDINALITY);
                }

                encodeTrade(buf, userId, 1.0, System.currentTimeMillis());
                while (pub.offer(buf, 0, TRADE_MSG_LEN) < 0) Thread.onSpinWait();

                nextSendNs += intervalNs;
            }

            long deadline = System.nanoTime() + 2_000_000_000L;
            while (levelCompleted.get() < probeCount && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            levelConsumerRunning.set(false);
            levelConsumer.join(1000);
            levelSub.close();

            probeBase += PROBES_PER_LEVEL;

            LatencyRecorder.LatencyStats s = levelRecorder.compute();
            report.append(String.format("  %-10s | %-7d | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                    fmtTps(targetTps), s.sampleCount(),
                    fmtLat(s.p50Ns()), fmtLat(s.p90Ns()), fmtLat(s.p95Ns()),
                    fmtLat(s.p99Ns()), fmtLat(s.p999Ns()), fmtLat(s.maxNs())));
        }

        consumerRunning.set(false);
        consumerThread.join(1000);
        report.append(String.format("%n"));
        report.append(footer());

        String fullReport = report.toString();
        System.out.println(fullReport);

        File reportFile = new File("target/aeron-ingestion-report.txt");
        try (PrintWriter pw = new PrintWriter(reportFile)) { pw.print(fullReport); }
        LOG.info("Report saved to {}", reportFile.getAbsolutePath());

        pub.close();
        sub.close();
        aggregator.close();
        aeron.close();
        driver.close();
        mapFile.delete();
    }

    private static void encodeTrade(UnsafeBuffer buf, long userId, double volume, long timestampMs) {
        buf.putLong(0, userId);
        buf.putDouble(8, volume);
        buf.putLong(16, timestampMs);
        buf.putInt(24, SYMBOL_BYTES.length);
        buf.putBytes(28, SYMBOL_BYTES);
    }

    private void sendBurst(Publication pub, long baseUserId, int count) {
        UnsafeBuffer buf = new UnsafeBuffer(BufferUtil.allocateDirectAligned(256, 64));
        for (int i = 0; i < count; i++) {
            long userId = baseUserId + (i % LOAD_USER_CARDINALITY);
            encodeTrade(buf, userId, 1.0, System.currentTimeMillis());
            while (pub.offer(buf, 0, TRADE_MSG_LEN) < 0) Thread.onSpinWait();
        }
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
                "     AERON INGESTION LATENCY BENCHMARK%n" +
                "============================================================%n" +
                "  Date      : %s%n" +
                "  Transport : Aeron IPC (embedded MediaDriver)%n" +
                "  Measured  : Publication.offer() → decode → addTrade()%n" +
                "              → Chronicle Map compute() returns%n" +
                "  JDK       : %s%n" +
                "============================================================%n%n",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                System.getProperty("java.version"));
    }

    private static String footer() {
        return String.format(
                "============================================================%n" +
                "  NOTES%n" +
                "  - Measures pure ingestion: IPC transport + binary decode%n" +
                "    + object allocation + Chronicle Map compute().%n" +
                "  - No snapshot emission involved.%n" +
                "  - Background load uses %,d user cardinality; probes use%n" +
                "    unique IDs measured independently.%n" +
                "  - nanoTime() is compared across sender and consumer%n" +
                "    threads within the same JVM.%n" +
                "============================================================%n",
                LOAD_USER_CARDINALITY);
    }
}
