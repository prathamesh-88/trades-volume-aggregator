package com.coindcx.aggregator.consumer;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * Covers {@link KafkaTradeConsumer#KafkaTradeConsumer(AppConfig, VolumeAggregator)} wiring
 * without a real broker, via constructed {@link KafkaConsumer} mocks.
 */
class KafkaTradeConsumerFactoryTest {

    @Test
    void appConfigConstructorBuildsKafkaConsumerFromConfig(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("kf.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        p.setProperty("kafka.bootstrap.servers", "localhost:19092");
        p.setProperty("kafka.consumer.topic", "factory-topic");
        p.setProperty("kafka.group.id", "factory-group");
        AppConfig config = new AppConfig(p);

        try (VolumeAggregator agg = new VolumeAggregator(config);
                MockedConstruction<KafkaConsumer> constructed = mockConstruction(
                        KafkaConsumer.class,
                        (mock, ctx) ->
                                when(mock.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty()))) {

            KafkaTradeConsumer consumer = new KafkaTradeConsumer(config, agg);
            assertThat(constructed.constructed()).hasSize(1);

            Thread t = new Thread(consumer);
            t.start();
            Thread.sleep(120);
            consumer.close();
            t.join(10_000);
        }
    }
}
