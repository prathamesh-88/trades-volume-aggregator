package com.coindcx.aggregator.consumer;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.VolumeSnapshot;
import io.aeron.logbuffer.Header;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AeronTradeSubscriberTest {

    @Test
    void onFragmentDecodesLittleEndianPayload(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("aeron.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            io.aeron.Subscription subscription = mock(io.aeron.Subscription.class);
            AeronTradeSubscriber sub = new AeronTradeSubscriber(subscription, agg, new NoOpIdleStrategy());

            String symbol = "BTC";
            byte[] sym = symbol.getBytes(StandardCharsets.UTF_8);
            int frameLen = 28 + sym.length;
            UnsafeBuffer buf = new UnsafeBuffer(new byte[frameLen]);
            int off = 0;
            buf.putLong(off + 0, 99L);
            buf.putDouble(off + 8, 1.25);
            buf.putLong(off + 16, 555L);
            buf.putInt(off + 24, sym.length);
            buf.putBytes(off + 28, sym);

            Header header = mock(Header.class);
            sub.onFragment(buf, off, frameLen, header);

            assertThat(agg.size()).isEqualTo(1);
            VolumeSnapshot snap = agg.getSnapshots().iterator().next();
            assertThat(snap.getUserId()).isEqualTo(99L);
            assertThat(snap.getTotalVolume()).isEqualTo(1.25);
            assertThat(snap.getLastUpdatedMs()).isEqualTo(555L);
        }
    }

    @Test
    void onFragmentCorruptDataDoesNotPropagate(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("aeron2.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            io.aeron.Subscription subscription = mock(io.aeron.Subscription.class);
            AeronTradeSubscriber sub = new AeronTradeSubscriber(subscription, agg, new NoOpIdleStrategy());
            UnsafeBuffer buf = new UnsafeBuffer(new byte[4]);
            sub.onFragment(buf, 0, 4, mock(Header.class));
            assertThat(agg.size()).isZero();
        }
    }

    @Test
    void runLoopStopsWhenClosed(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("aeron3.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            io.aeron.Subscription subscription = mock(io.aeron.Subscription.class);
            AtomicInteger polls = new AtomicInteger();
            when(subscription.poll(any(), anyInt())).thenAnswer(inv -> {
                polls.incrementAndGet();
                return 0;
            });

            AeronTradeSubscriber sub = new AeronTradeSubscriber(subscription, agg, new NoOpIdleStrategy());
            Thread t = new Thread(sub);
            t.start();
            Thread.sleep(50);
            sub.close();
            t.join(5_000);
            assertThat(polls.get()).isPositive();
            Mockito.verify(subscription).close();
        }
    }
}
