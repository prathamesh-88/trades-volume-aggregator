package com.coindcx.aggregator.publisher;

import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.VolumeSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Collection;
import java.util.Properties;

public final class KafkaSnapshotPublisher implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaSnapshotPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaSnapshotPublisher(AppConfig config) {
        this.objectMapper = new ObjectMapper();
        this.topic = config.kafkaProducerTopic();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");

        this.producer = new KafkaProducer<>(props);
        LOG.info("Kafka snapshot publisher created for topic '{}'", topic);
    }

    public void publish(Collection<VolumeSnapshot> snapshots) {
        for (VolumeSnapshot snapshot : snapshots) {
            try {
                String key = String.valueOf(snapshot.getUserId());
                String value = objectMapper.writeValueAsString(snapshot);
                producer.send(new ProducerRecord<>(topic, key, value), (metadata, exception) -> {
                    if (exception != null) {
                        LOG.warn("Failed to publish snapshot for user {}: {}",
                                snapshot.getUserId(), exception.getMessage());
                    }
                });
            } catch (JsonProcessingException e) {
                LOG.warn("Failed to serialise snapshot for user {}: {}",
                        snapshot.getUserId(), e.getMessage());
            }
        }
        producer.flush();
    }

    @Override
    public void close() {
        LOG.info("Closing Kafka snapshot publisher");
        producer.close();
    }
}
