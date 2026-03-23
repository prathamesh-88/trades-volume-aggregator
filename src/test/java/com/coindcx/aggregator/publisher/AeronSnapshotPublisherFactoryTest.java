package com.coindcx.aggregator.publisher;

import com.coindcx.aggregator.config.AppConfig;
import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link AeronSnapshotPublisher#AeronSnapshotPublisher(Aeron, AppConfig)}.
 */
class AeronSnapshotPublisherFactoryTest {

    @Test
    void appConfigConstructorAddsPublication() {
        Aeron aeron = mock(Aeron.class);
        ConcurrentPublication publication = mock(ConcurrentPublication.class);
        when(aeron.addPublication(anyString(), anyInt())).thenReturn(publication);

        Properties p = new Properties();
        p.setProperty("aeron.publisher.channel", "aeron:udp?endpoint=localhost:50001");
        p.setProperty("aeron.publisher.stream.id", "77");
        AppConfig config = new AppConfig(p);

        try (AeronSnapshotPublisher publisher = new AeronSnapshotPublisher(aeron, config)) {
            verify(aeron).addPublication("aeron:udp?endpoint=localhost:50001", 77);
        }

        verify(publication).close();
    }
}
