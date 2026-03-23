package com.coindcx.aggregator.aggregator;

import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import com.coindcx.aggregator.model.VolumeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class VolumeAggregatorTest {

    @Test
    void addTradeAccumulatesPerUserAndTracksLastUpdated(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("vol.dat").toString());
        p.setProperty("chronicle.map.entries", "1000");
        AppConfig config = new AppConfig(p);

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            agg.addTrade(new TradeEvent(1L, "A", 1.0, 10L));
            agg.addTrade(new TradeEvent(1L, "B", 2.5, 20L));
            agg.addTrade(new TradeEvent(2L, "C", 3.0, 30L));

            assertThat(agg.size()).isEqualTo(2);

            Collection<VolumeSnapshot> snaps = agg.getSnapshots();
            VolumeSnapshot u1 = snaps.stream().filter(s -> s.getUserId() == 1L).findFirst().orElseThrow();
            VolumeSnapshot u2 = snaps.stream().filter(s -> s.getUserId() == 2L).findFirst().orElseThrow();

            assertThat(u1.getTotalVolume()).isEqualTo(3.5);
            assertThat(u1.getLastUpdatedMs()).isEqualTo(20L);
            assertThat(u2.getTotalVolume()).isEqualTo(3.0);
            assertThat(u2.getLastUpdatedMs()).isEqualTo(30L);
        }
    }

    @Test
    void concurrentAddsAreSafe(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("vol-concurrent.dat").toString());
        p.setProperty("chronicle.map.entries", "10000");
        AppConfig config = new AppConfig(p);

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            int threads = 8;
            int tradesPerThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final long userId = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < tradesPerThread; i++) {
                            agg.addTrade(new TradeEvent(userId, "S", 1.0, i));
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(agg.size()).isEqualTo(threads);
            List<Double> volumes = agg.getSnapshots().stream()
                    .map(VolumeSnapshot::getTotalVolume)
                    .sorted()
                    .collect(Collectors.toList());
            assertThat(volumes).hasSize(threads);
            for (double v : volumes) {
                assertThat(v).isEqualTo(tradesPerThread, offset(1e-9));
            }
        }
    }
}
