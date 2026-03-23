package com.coindcx.aggregator.consumer;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import io.aeron.Aeron;
import io.aeron.Subscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link AeronTradeSubscriber#AeronTradeSubscriber(Aeron, AppConfig, VolumeAggregator)}.
 */
class AeronTradeSubscriberFactoryTest {

    @Test
    void appConfigConstructorSubscribesViaAeron(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("asf.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        p.setProperty("aeron.subscriber.channel", "aeron:udp?endpoint=localhost:49999");
        p.setProperty("aeron.subscriber.stream.id", "42");
        AppConfig config = new AppConfig(p);

        Aeron aeron = mock(Aeron.class);
        Subscription subscription = mock(Subscription.class);
        when(aeron.addSubscription(anyString(), anyInt())).thenReturn(subscription);

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            AeronTradeSubscriber sub = new AeronTradeSubscriber(aeron, config, agg);
            verify(aeron).addSubscription("aeron:udp?endpoint=localhost:49999", 42);
            sub.close();
        }
    }
}
