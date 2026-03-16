package com.coindcx.aggregator.consumer;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KafkaTradeConsumer implements Runnable, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTradeConsumer.class);

    private final KafkaConsumer<String, String> consumer;
    private final VolumeAggregator aggregator;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KafkaTradeConsumer(AppConfig config, VolumeAggregator aggregator) {
        this.aggregator = aggregator;
        this.objectMapper = new ObjectMapper();
        this.topic = config.kafkaConsumerTopic();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.kafkaGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        this.consumer = new KafkaConsumer<>(props);
        LOG.info("Kafka consumer created for topic '{}' at {}", topic, config.kafkaBootstrapServers());
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(Collections.singletonList(topic));
            LOG.info("Kafka consumer subscribed to '{}'", topic);

            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        TradeEvent event = objectMapper.readValue(record.value(), TradeEvent.class);
                        aggregator.addTrade(event);
                    } catch (Exception e) {
                        LOG.warn("Failed to deserialise trade event at offset {}: {}",
                                record.offset(), e.getMessage());
                    }
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        } finally {
            consumer.close();
            LOG.info("Kafka consumer closed");
        }
    }

    @Override
    public void close() {
        LOG.info("Shutting down Kafka consumer");
        running.set(false);
        consumer.wakeup();
    }
}
