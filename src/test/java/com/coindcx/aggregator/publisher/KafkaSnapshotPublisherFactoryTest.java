package com.coindcx.aggregator.publisher;

import com.coindcx.aggregator.config.AppConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

/**
 * Covers {@link KafkaSnapshotPublisher#KafkaSnapshotPublisher(AppConfig)} without a real broker.
 */
class KafkaSnapshotPublisherFactoryTest {

    @Test
    void appConfigConstructorBuildsProducerFromConfig() {
        Properties p = new Properties();
        p.setProperty("kafka.bootstrap.servers", "localhost:19092");
        p.setProperty("kafka.producer.topic", "snap-out");
        AppConfig config = new AppConfig(p);

        try (MockedConstruction<KafkaProducer> constructed = mockConstruction(KafkaProducer.class)) {
            KafkaSnapshotPublisher publisher = new KafkaSnapshotPublisher(config);
            assertThat(constructed.constructed()).hasSize(1);
            publisher.close();
        }
    }
}
