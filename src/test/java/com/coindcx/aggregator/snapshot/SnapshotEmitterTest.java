package com.coindcx.aggregator.snapshot;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import com.coindcx.aggregator.model.VolumeSnapshot;
import com.coindcx.aggregator.publisher.AeronSnapshotPublisher;
import com.coindcx.aggregator.publisher.KafkaSnapshotPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotEmitterTest {

    @Test
    void emitSnapshotsSetsTimestampAndPublishes(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("se.dat").toString());
        p.setProperty("chronicle.map.entries", "100");

        try (VolumeAggregator agg = new VolumeAggregator(new AppConfig(p))) {
            agg.addTrade(new TradeEvent(1L, "S", 2.0, 10L));

            KafkaSnapshotPublisher kafka = mock(KafkaSnapshotPublisher.class);
            AeronSnapshotPublisher aeron = mock(AeronSnapshotPublisher.class);
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

            SnapshotEmitter emitter = new SnapshotEmitter(agg, kafka, aeron, scheduler, 1_000L);
            emitter.emitSnapshots();

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<VolumeSnapshot>> cap =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(kafka).publish(cap.capture());
            verify(aeron).publish(cap.getValue());
            VolumeSnapshot s = cap.getValue().get(0);
            assertThat(s.getSnapshotTimestampMs()).isPositive();
            assertThat(s.getUserId()).isEqualTo(1L);
        }
    }

    @Test
    void emitSnapshotsSwallowsPublisherFailures(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("se2.dat").toString());
        p.setProperty("chronicle.map.entries", "100");

        try (VolumeAggregator agg = new VolumeAggregator(new AppConfig(p))) {
            agg.addTrade(new TradeEvent(1L, "S", 1.0, 1L));
            KafkaSnapshotPublisher kafka = mock(KafkaSnapshotPublisher.class);
            AeronSnapshotPublisher aeron = mock(AeronSnapshotPublisher.class);
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            doThrow(new RuntimeException("fail")).when(kafka).publish(any());

            SnapshotEmitter emitter = new SnapshotEmitter(agg, kafka, aeron, scheduler, 1L);
            emitter.emitSnapshots();
        }
    }

    @Test
    void closeShutsDownScheduler() throws Exception {
        VolumeAggregator agg = mock(VolumeAggregator.class);
        KafkaSnapshotPublisher kafka = mock(KafkaSnapshotPublisher.class);
        AeronSnapshotPublisher aeron = mock(AeronSnapshotPublisher.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);

        SnapshotEmitter emitter = new SnapshotEmitter(agg, kafka, aeron, scheduler, 1L);
        emitter.close();

        verify(scheduler).shutdown();
        verify(scheduler).awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void closeForcesShutdownNowWhenNotTerminated() throws Exception {
        VolumeAggregator agg = mock(VolumeAggregator.class);
        KafkaSnapshotPublisher kafka = mock(KafkaSnapshotPublisher.class);
        AeronSnapshotPublisher aeron = mock(AeronSnapshotPublisher.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);

        SnapshotEmitter emitter = new SnapshotEmitter(agg, kafka, aeron, scheduler, 1L);
        emitter.close();

        verify(scheduler).shutdownNow();
    }

    @Test
    void closeInterruptedUsesShutdownNow() throws Exception {
        VolumeAggregator agg = mock(VolumeAggregator.class);
        KafkaSnapshotPublisher kafka = mock(KafkaSnapshotPublisher.class);
        AeronSnapshotPublisher aeron = mock(AeronSnapshotPublisher.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        SnapshotEmitter emitter = new SnapshotEmitter(agg, kafka, aeron, scheduler, 1L);
        emitter.close();

        verify(scheduler).shutdownNow();
        assertThat(Thread.interrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void startSchedulesEmitter() {
        VolumeAggregator agg = mock(VolumeAggregator.class);
        KafkaSnapshotPublisher kafka = mock(KafkaSnapshotPublisher.class);
        AeronSnapshotPublisher aeron = mock(AeronSnapshotPublisher.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        SnapshotEmitter emitter = new SnapshotEmitter(agg, kafka, aeron, scheduler, 250L);
        emitter.start();

        Mockito.verify(scheduler).scheduleAtFixedRate(
                Mockito.any(Runnable.class),
                Mockito.eq(250L),
                Mockito.eq(250L),
                Mockito.eq(TimeUnit.MILLISECONDS));
        emitter.close();
    }

    @Test
    void appConfigConstructorUsesSnapshotIntervalFromConfig() {
        Properties p = new Properties();
        p.setProperty("snapshot.interval.ms", "333");
        AppConfig config = new AppConfig(p);
        VolumeAggregator agg = mock(VolumeAggregator.class);
        KafkaSnapshotPublisher kafka = mock(KafkaSnapshotPublisher.class);
        AeronSnapshotPublisher aeron = mock(AeronSnapshotPublisher.class);

        SnapshotEmitter emitter = new SnapshotEmitter(config, agg, kafka, aeron);
        emitter.start();
        emitter.close();
    }
}
