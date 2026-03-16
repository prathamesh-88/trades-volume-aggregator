package com.coindcx.aggregator.publisher;

import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.VolumeSnapshot;
import io.aeron.Aeron;
import io.aeron.Publication;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Collection;

/**
 * Publishes volume snapshots to an Aeron channel.
 *
 * Wire format (little-endian):
 * <pre>
 *   offset  0: userId             (long,   8 bytes)
 *   offset  8: totalVolume        (double, 8 bytes)
 *   offset 16: lastUpdatedMs      (long,   8 bytes)
 *   offset 24: snapshotTimestampMs(long,   8 bytes)
 * </pre>
 * Total: 32 bytes per snapshot.
 */
public final class AeronSnapshotPublisher implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(AeronSnapshotPublisher.class);

    private static final int USER_ID_OFFSET = 0;
    private static final int TOTAL_VOLUME_OFFSET = 8;
    private static final int LAST_UPDATED_OFFSET = 16;
    private static final int SNAPSHOT_TS_OFFSET = 24;
    private static final int MESSAGE_LENGTH = 32;
    private static final int MAX_OFFER_RETRIES = 3;

    private final Publication publication;
    private final UnsafeBuffer buffer;

    public AeronSnapshotPublisher(Aeron aeron, AppConfig config) {
        String channel = config.aeronPublisherChannel();
        int streamId = config.aeronPublisherStreamId();
        this.publication = aeron.addPublication(channel, streamId);
        this.buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(MESSAGE_LENGTH, 64));

        LOG.info("Aeron snapshot publisher created on channel={} streamId={}", channel, streamId);
    }

    public void publish(Collection<VolumeSnapshot> snapshots) {
        for (VolumeSnapshot snapshot : snapshots) {
            encodeSnapshot(snapshot);
            offerWithRetry(snapshot.getUserId());
        }
    }

    private void encodeSnapshot(VolumeSnapshot snapshot) {
        buffer.putLong(USER_ID_OFFSET, snapshot.getUserId());
        buffer.putDouble(TOTAL_VOLUME_OFFSET, snapshot.getTotalVolume());
        buffer.putLong(LAST_UPDATED_OFFSET, snapshot.getLastUpdatedMs());
        buffer.putLong(SNAPSHOT_TS_OFFSET, snapshot.getSnapshotTimestampMs());
    }

    private void offerWithRetry(long userId) {
        for (int attempt = 0; attempt < MAX_OFFER_RETRIES; attempt++) {
            long result = publication.offer(buffer, 0, MESSAGE_LENGTH);
            if (result >= 0) {
                return;
            }

            if (result == Publication.BACK_PRESSURED) {
                LOG.debug("Aeron back-pressured on snapshot for user {}, retry {}", userId, attempt + 1);
                Thread.onSpinWait();
            } else if (result == Publication.NOT_CONNECTED) {
                LOG.debug("Aeron publication not connected for user {}", userId);
                return;
            } else if (result == Publication.CLOSED) {
                LOG.warn("Aeron publication closed, cannot publish snapshot for user {}", userId);
                return;
            } else if (result == Publication.MAX_POSITION_EXCEEDED) {
                LOG.error("Aeron max position exceeded for user {}", userId);
                return;
            }
        }
        LOG.debug("Aeron publish retries exhausted for user {}", userId);
    }

    @Override
    public void close() {
        LOG.info("Closing Aeron snapshot publisher");
        publication.close();
    }
}
