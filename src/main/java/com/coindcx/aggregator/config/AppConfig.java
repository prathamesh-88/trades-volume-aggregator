package com.coindcx.aggregator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);
    private static final String PROPERTIES_FILE = "application.properties";

    private final Properties properties;

    public AppConfig() {
        this.properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (in != null) {
                properties.load(in);
                LOG.info("Loaded configuration from {}", PROPERTIES_FILE);
            } else {
                LOG.warn("{} not found on classpath, using defaults", PROPERTIES_FILE);
            }
        } catch (IOException e) {
            LOG.error("Failed to load {}", PROPERTIES_FILE, e);
        }
    }

    private String get(String key, String defaultValue) {
        String envKey = key.replace('.', '_').toUpperCase();
        String envValue = System.getenv(envKey);
        if (envValue != null) {
            return envValue;
        }
        return properties.getProperty(key, defaultValue);
    }

    private int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    }

    private long getLong(String key, long defaultValue) {
        return Long.parseLong(get(key, String.valueOf(defaultValue)));
    }

    // ---- Kafka ----

    public String kafkaBootstrapServers() {
        return get("kafka.bootstrap.servers", "localhost:9092");
    }

    public String kafkaConsumerTopic() {
        return get("kafka.consumer.topic", "trade-events");
    }

    public String kafkaProducerTopic() {
        return get("kafka.producer.topic", "volume-snapshots");
    }

    public String kafkaGroupId() {
        return get("kafka.group.id", "volume-aggregator-group");
    }

    // ---- Aeron ----

    public String aeronDirectory() {
        return get("aeron.directory", System.getProperty("java.io.tmpdir") + "/aeron-volume-aggregator");
    }

    public String aeronSubscriberChannel() {
        return get("aeron.subscriber.channel", "aeron:udp?endpoint=localhost:40123");
    }

    public int aeronSubscriberStreamId() {
        return getInt("aeron.subscriber.stream.id", 1001);
    }

    public String aeronPublisherChannel() {
        return get("aeron.publisher.channel", "aeron:udp?endpoint=localhost:40124");
    }

    public int aeronPublisherStreamId() {
        return getInt("aeron.publisher.stream.id", 1002);
    }

    // ---- Snapshot ----

    public long snapshotIntervalMs() {
        return getLong("snapshot.interval.ms", 1000);
    }

    // ---- Chronicle Map ----

    public long chronicleMapEntries() {
        return getLong("chronicle.map.entries", 1_000_000);
    }

    public String chronicleMapFile() {
        return get("chronicle.map.file", System.getProperty("java.io.tmpdir") + "/volume-aggregator.dat");
    }
}
