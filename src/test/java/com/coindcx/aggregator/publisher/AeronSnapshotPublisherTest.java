package com.coindcx.aggregator.publisher;

import com.coindcx.aggregator.model.VolumeSnapshot;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AeronSnapshotPublisherTest {

    @Test
    void publishOffersEncodedSnapshot() {
        Publication publication = mock(Publication.class);
        when(publication.offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(128L);

        try (AeronSnapshotPublisher pub = new AeronSnapshotPublisher(publication)) {
            VolumeSnapshot s = new VolumeSnapshot(11L, 2.5, 30L, 40L);
            pub.publish(List.of(s));
        }

        ArgumentCaptor<UnsafeBuffer> buf = ArgumentCaptor.forClass(UnsafeBuffer.class);
        verify(publication).offer(buf.capture(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(32));
        UnsafeBuffer b = buf.getValue();
        assertThat(b.getLong(0)).isEqualTo(11L);
        assertThat(b.getDouble(8)).isEqualTo(2.5);
        assertThat(b.getLong(16)).isEqualTo(30L);
        assertThat(b.getLong(24)).isEqualTo(40L);
        verify(publication).close();
    }

    @Test
    void backPressureRetriesUntilSuccess() {
        Publication publication = mock(Publication.class);
        AtomicInteger calls = new AtomicInteger();
        when(publication.offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    int c = calls.incrementAndGet();
                    if (c < 2) {
                        return Publication.BACK_PRESSURED;
                    }
                    return 1L;
                });

        try (AeronSnapshotPublisher pub = new AeronSnapshotPublisher(publication)) {
            pub.publish(List.of(new VolumeSnapshot(1L, 1.0, 1L, 1L)));
        }

        verify(publication, times(2)).offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(32));
    }

    @Test
    void notConnectedStopsRetries() {
        Publication publication = mock(Publication.class);
        when(publication.offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Publication.NOT_CONNECTED);

        try (AeronSnapshotPublisher pub = new AeronSnapshotPublisher(publication)) {
            pub.publish(List.of(new VolumeSnapshot(1L, 1.0, 1L, 1L)));
        }

        verify(publication, times(1)).offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(32));
    }

    @Test
    void closedStopsRetries() {
        Publication publication = mock(Publication.class);
        when(publication.offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Publication.CLOSED);

        try (AeronSnapshotPublisher pub = new AeronSnapshotPublisher(publication)) {
            pub.publish(List.of(new VolumeSnapshot(1L, 1.0, 1L, 1L)));
        }

        verify(publication, times(1)).offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(32));
    }

    @Test
    void maxPositionExceededStopsRetries() {
        Publication publication = mock(Publication.class);
        when(publication.offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Publication.MAX_POSITION_EXCEEDED);

        try (AeronSnapshotPublisher pub = new AeronSnapshotPublisher(publication)) {
            pub.publish(List.of(new VolumeSnapshot(1L, 1.0, 1L, 1L)));
        }

        verify(publication, times(1)).offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(32));
    }

    @Test
    void retriesExhaustedAfterBackPressure() {
        Publication publication = mock(Publication.class);
        when(publication.offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Publication.BACK_PRESSURED);

        try (AeronSnapshotPublisher pub = new AeronSnapshotPublisher(publication)) {
            pub.publish(List.of(new VolumeSnapshot(1L, 1.0, 1L, 1L)));
        }

        verify(publication, times(3)).offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(32));
    }

    @Test
    void unknownNegativeOfferResultRetriesUntilSuccess() {
        Publication publication = mock(Publication.class);
        AtomicInteger calls = new AtomicInteger();
        when(publication.offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    int c = calls.incrementAndGet();
                    if (c == 1) {
                        return -999L;
                    }
                    return 1L;
                });

        try (AeronSnapshotPublisher pub = new AeronSnapshotPublisher(publication)) {
            pub.publish(List.of(new VolumeSnapshot(1L, 1.0, 1L, 1L)));
        }

        verify(publication, times(2)).offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(32));
    }
}
