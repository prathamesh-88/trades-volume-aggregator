package com.coindcx.aggregator.config;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigTest {

    private static final String APP_PROPS = "application.properties";

    @Test
    void overridesMergeOnTopOfClasspathDefaults() {
        Properties overrides = new Properties();
        overrides.setProperty("kafka.bootstrap.servers", "broker:9092");
        overrides.setProperty("kafka.consumer.topic", "custom-trades");
        overrides.setProperty("snapshot.interval.ms", "2500");
        overrides.setProperty("chronicle.map.entries", "5000");

        AppConfig config = new AppConfig(overrides);

        assertThat(config.kafkaBootstrapServers()).isEqualTo("broker:9092");
        assertThat(config.kafkaConsumerTopic()).isEqualTo("custom-trades");
        assertThat(config.snapshotIntervalMs()).isEqualTo(2500L);
        assertThat(config.chronicleMapEntries()).isEqualTo(5000L);
        assertThat(config.kafkaProducerTopic()).isNotBlank();
    }

    @Test
    void defaultConstructorLoadsClasspathProperties() {
        AppConfig config = new AppConfig();
        assertThat(config.kafkaBootstrapServers()).isNotBlank();
        assertThat(config.aeronSubscriberStreamId()).isPositive();
        assertThat(config.aeronPublisherStreamId()).isPositive();
    }

    @Test
    void nullOverridesBehavesLikeDefaultConstructor() {
        AppConfig withNull = new AppConfig(null);
        AppConfig baseline = new AppConfig();
        assertThat(withNull.kafkaGroupId()).isEqualTo(baseline.kafkaGroupId());
    }

    @Test
    void invalidIntPropertyThrows() {
        Properties overrides = new Properties();
        overrides.setProperty("aeron.subscriber.stream.id", "not-a-number");
        AppConfig config = new AppConfig(overrides);
        assertThatThrownBy(config::aeronSubscriberStreamId)
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void invalidLongPropertyThrows() {
        Properties overrides = new Properties();
        overrides.setProperty("snapshot.interval.ms", "x");
        AppConfig config = new AppConfig(overrides);
        assertThatThrownBy(config::snapshotIntervalMs)
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void invalidLongForChronicleEntriesThrows() {
        Properties overrides = new Properties();
        overrides.setProperty("chronicle.map.entries", "bad");
        AppConfig config = new AppConfig(overrides);
        assertThatThrownBy(config::chronicleMapEntries).isInstanceOf(NumberFormatException.class);
    }

    @Test
    @SetEnvironmentVariable(key = "KAFKA_BOOTSTRAP_SERVERS", value = "from-env:9092")
    void envVarOverridesClasspathForKafkaBootstrap() {
        AppConfig config = new AppConfig();
        assertThat(config.kafkaBootstrapServers()).isEqualTo("from-env:9092");
    }

    @Test
    void whenPropertiesFileMissingUsesStringDefaults() {
        ClassLoader noProps =
                new ClassLoader(null) {
                    @Override
                    public InputStream getResourceAsStream(String name) {
                        if (APP_PROPS.equals(name)) {
                            return null;
                        }
                        return null;
                    }
                };
        AppConfig config = new AppConfig(null, noProps);
        assertThat(config.kafkaBootstrapServers()).isEqualTo("localhost:9092");
        assertThat(config.kafkaConsumerTopic()).isEqualTo("trade-events");
        assertThat(config.aeronSubscriberStreamId()).isEqualTo(1001);
        assertThat(config.snapshotIntervalMs()).isEqualTo(1000L);
        assertThat(config.chronicleMapEntries()).isEqualTo(1_000_000L);
    }

    @Test
    void loadClasspathIOExceptionIsLoggedAndContinuing() {
        ClassLoader broken =
                new ClassLoader(null) {
                    @Override
                    public InputStream getResourceAsStream(String name) {
                        if (!APP_PROPS.equals(name)) {
                            return null;
                        }
                        return new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("simulated read failure");
                            }
                        };
                    }
                };
        AppConfig config = new AppConfig(null, broken);
        assertThat(config.kafkaBootstrapServers()).isEqualTo("localhost:9092");
    }

    @Test
    void explicitClassLoaderStillMergesOverrides() {
        ClassLoader noProps =
                new ClassLoader(null) {
                    @Override
                    public InputStream getResourceAsStream(String name) {
                        return null;
                    }
                };
        Properties overrides = new Properties();
        overrides.setProperty("kafka.bootstrap.servers", "override:9092");
        AppConfig config = new AppConfig(overrides, noProps);
        assertThat(config.kafkaBootstrapServers()).isEqualTo("override:9092");
    }

    @Test
    void allPublicAccessorsReturnConsistentValues() {
        Properties overrides = new Properties();
        overrides.setProperty("kafka.bootstrap.servers", "k:1");
        overrides.setProperty("kafka.consumer.topic", "ct");
        overrides.setProperty("kafka.producer.topic", "pt");
        overrides.setProperty("kafka.group.id", "gid");
        overrides.setProperty("aeron.directory", "/tmp/aeron");
        overrides.setProperty("aeron.subscriber.channel", "aeron:udp?endpoint=1:1");
        overrides.setProperty("aeron.subscriber.stream.id", "11");
        overrides.setProperty("aeron.publisher.channel", "aeron:udp?endpoint=2:2");
        overrides.setProperty("aeron.publisher.stream.id", "22");
        overrides.setProperty("snapshot.interval.ms", "500");
        overrides.setProperty("chronicle.map.entries", "99");
        overrides.setProperty("chronicle.map.file", "/tmp/cm.dat");
        AppConfig c = new AppConfig(overrides);
        assertThat(c.kafkaBootstrapServers()).isEqualTo("k:1");
        assertThat(c.kafkaConsumerTopic()).isEqualTo("ct");
        assertThat(c.kafkaProducerTopic()).isEqualTo("pt");
        assertThat(c.kafkaGroupId()).isEqualTo("gid");
        assertThat(c.aeronDirectory()).isEqualTo("/tmp/aeron");
        assertThat(c.aeronSubscriberChannel()).isEqualTo("aeron:udp?endpoint=1:1");
        assertThat(c.aeronSubscriberStreamId()).isEqualTo(11);
        assertThat(c.aeronPublisherChannel()).isEqualTo("aeron:udp?endpoint=2:2");
        assertThat(c.aeronPublisherStreamId()).isEqualTo(22);
        assertThat(c.snapshotIntervalMs()).isEqualTo(500L);
        assertThat(c.chronicleMapEntries()).isEqualTo(99L);
        assertThat(c.chronicleMapFile()).isEqualTo("/tmp/cm.dat");
    }
}
