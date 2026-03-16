package com.coindcx.aggregator.aggregator;

import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import com.coindcx.aggregator.model.VolumeSnapshot;
import net.openhft.chronicle.map.ChronicleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class VolumeAggregator implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(VolumeAggregator.class);

    private final ChronicleMap<Long, VolumeSnapshot> map;

    public VolumeAggregator(AppConfig config) throws IOException {
        File mapFile = new File(config.chronicleMapFile());
        this.map = ChronicleMap
                .of(Long.class, VolumeSnapshot.class)
                .name("volume-aggregator")
                .entries(config.chronicleMapEntries())
                .averageValueSize(64)
                .createPersistedTo(mapFile);

        LOG.info("Chronicle Map initialised with capacity {} at {}",
                config.chronicleMapEntries(), mapFile.getAbsolutePath());
    }

    /**
     * Atomically accumulates the trade volume for the given user.
     * Uses Chronicle Map's segment-level locking for thread safety.
     */
    public void addTrade(TradeEvent event) {
        long userId = event.getUserId();

        map.compute(userId, (key, existing) -> {
            if (existing == null) {
                existing = new VolumeSnapshot();
                existing.setUserId(userId);
                existing.setTotalVolume(0.0);
            }
            existing.setTotalVolume(existing.getTotalVolume() + event.getVolume());
            existing.setLastUpdatedMs(event.getTimestampMs());
            return existing;
        });
    }

    /**
     * Returns a point-in-time copy of all user volume aggregates.
     */
    public Collection<VolumeSnapshot> getSnapshots() {
        List<VolumeSnapshot> snapshots = new ArrayList<>((int) map.size());
        map.forEach((userId, snapshot) -> snapshots.add(snapshot.copy()));
        return snapshots;
    }

    public long size() {
        return map.size();
    }

    @Override
    public void close() {
        LOG.info("Closing Chronicle Map ({} entries)", map.size());
        map.close();
    }
}
