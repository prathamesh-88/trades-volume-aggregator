package com.coindcx.aggregator.snapshot;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.VolumeSnapshot;
import com.coindcx.aggregator.publisher.AeronSnapshotPublisher;
import com.coindcx.aggregator.publisher.KafkaSnapshotPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SnapshotEmitter implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotEmitter.class);

    private final VolumeAggregator aggregator;
    private final KafkaSnapshotPublisher kafkaPublisher;
    private final AeronSnapshotPublisher aeronPublisher;
    private final ScheduledExecutorService scheduler;
    private final long intervalMs;

    public SnapshotEmitter(AppConfig config,
                           VolumeAggregator aggregator,
                           KafkaSnapshotPublisher kafkaPublisher,
                           AeronSnapshotPublisher aeronPublisher) {
        this.aggregator = aggregator;
        this.kafkaPublisher = kafkaPublisher;
        this.aeronPublisher = aeronPublisher;
        this.intervalMs = config.snapshotIntervalMs();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-emitter");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::emitSnapshots, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        LOG.info("Snapshot emitter started with interval {}ms", intervalMs);
    }

    private void emitSnapshots() {
        try {
            long startNs = System.nanoTime();

            Collection<VolumeSnapshot> snapshots = aggregator.getSnapshots();
            long now = System.currentTimeMillis();
            for (VolumeSnapshot s : snapshots) {
                s.setSnapshotTimestampMs(now);
            }

            kafkaPublisher.publish(snapshots);
            aeronPublisher.publish(snapshots);

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            LOG.info("Emitted {} snapshots in {}ms", snapshots.size(), elapsedMs);
        } catch (Exception e) {
            LOG.error("Snapshot emission failed", e);
        }
    }

    @Override
    public void close() {
        LOG.info("Shutting down snapshot emitter");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
