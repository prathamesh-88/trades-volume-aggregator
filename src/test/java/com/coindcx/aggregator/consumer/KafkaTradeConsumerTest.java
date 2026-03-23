package com.coindcx.aggregator.consumer;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaTradeConsumerTest {

    @Test
    void deserialisesValidJsonAndAddsTrade(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("k.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);

        String json = """
                {"userId":5,"symbol":"X","volume":2.0,"timestampMs":123}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 1L, null, json);
        TopicPartition tp = new TopicPartition("t", 0);
        Map<TopicPartition, List<ConsumerRecord<String, String>>> data = new HashMap<>();
        data.put(tp, List.of(record));
        ConsumerRecords<String, String> batch = new ConsumerRecords<>(data);

        when(consumer.poll(any(Duration.class)))
                .thenReturn(batch)
                .thenAnswer(inv -> {
                    Thread.sleep(20);
                    return ConsumerRecords.empty();
                });

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            KafkaTradeConsumer kc = new KafkaTradeConsumer(consumer, agg, "t", new ObjectMapper());
            Thread t = new Thread(kc);
            t.start();
            Thread.sleep(150);
            kc.close();
            t.join(5_000);
            assertThat(agg.size()).isEqualTo(1);
            assertThat(agg.getSnapshots().iterator().next().getTotalVolume()).isEqualTo(2.0);
        }

        verify(consumer, atLeastOnce()).subscribe(Collections.singletonList("t"));
    }

    @Test
    void malformedJsonIsSkipped(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("k2.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 2L, null, "not-json");
        TopicPartition tp = new TopicPartition("t", 0);
        Map<TopicPartition, List<ConsumerRecord<String, String>>> data = Map.of(tp, List.of(record));
        when(consumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(data))
                .thenAnswer(inv -> {
                    Thread.sleep(20);
                    return ConsumerRecords.empty();
                });

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            KafkaTradeConsumer kc = new KafkaTradeConsumer(consumer, agg, "t", new ObjectMapper());
            Thread t = new Thread(kc);
            t.start();
            Thread.sleep(150);
            kc.close();
            t.join(5_000);
            assertThat(agg.size()).isZero();
        }
    }

    @Test
    void wakeupAfterCloseDoesNotRethrow(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("k-wakeup.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
        CountDownLatch firstPoll = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        when(consumer.poll(any(Duration.class)))
                .thenAnswer(
                        inv -> {
                            int n = polls.incrementAndGet();
                            if (n == 1) {
                                firstPoll.countDown();
                                return ConsumerRecords.empty();
                            }
                            throw new WakeupException();
                        });

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            KafkaTradeConsumer kc = new KafkaTradeConsumer(consumer, agg, "t", new ObjectMapper());
            Thread t = new Thread(kc);
            t.start();
            assertThat(firstPoll.await(5, TimeUnit.SECONDS)).isTrue();
            kc.close();
            t.join(10_000);
        }

        verify(consumer).close();
    }

    @Test
    void wakeupWhileRunningRethrowsAndClosesConsumer(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("k-wakeup2.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
        when(consumer.poll(any(Duration.class))).thenThrow(new WakeupException());

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            KafkaTradeConsumer kc = new KafkaTradeConsumer(consumer, agg, "t", new ObjectMapper());
            Thread t = new Thread(kc);
            t.start();
            t.join(10_000);
        }

        verify(consumer).close();
    }

    @Test
    void loopExitsWhenRunningFalseAfterPollWithoutWakeupException(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("k-loop.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
        CountDownLatch firstPollDone = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        when(consumer.poll(any(Duration.class)))
                .thenAnswer(
                        inv -> {
                            int n = polls.incrementAndGet();
                            if (n == 1) {
                                firstPollDone.countDown();
                                return ConsumerRecords.empty();
                            }
                            Thread.sleep(400);
                            return ConsumerRecords.empty();
                        });

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            KafkaTradeConsumer kc = new KafkaTradeConsumer(consumer, agg, "t", new ObjectMapper());
            Thread t = new Thread(kc);
            t.start();
            assertThat(firstPollDone.await(5, TimeUnit.SECONDS)).isTrue();
            kc.close();
            t.join(15_000);
        }

        verify(consumer).close();
    }

    @Test
    void multipleRecordsInSinglePollAreAllProcessed(@TempDir Path dir) throws Exception {
        Properties p = new Properties();
        p.setProperty("chronicle.map.file", dir.resolve("k-multi.dat").toString());
        p.setProperty("chronicle.map.entries", "100");
        AppConfig config = new AppConfig(p);

        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
        String j1 = "{\"userId\":1,\"symbol\":\"A\",\"volume\":1.0,\"timestampMs\":1}";
        String j2 = "{\"userId\":2,\"symbol\":\"B\",\"volume\":2.0,\"timestampMs\":2}";
        TopicPartition tp = new TopicPartition("t", 0);
        Map<TopicPartition, List<ConsumerRecord<String, String>>> data =
                Map.of(
                        tp,
                        List.of(
                                new ConsumerRecord<>("t", 0, 1L, null, j1),
                                new ConsumerRecord<>("t", 0, 2L, null, j2)));
        when(consumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(data))
                .thenAnswer(inv -> {
                    Thread.sleep(20);
                    return ConsumerRecords.empty();
                });

        try (VolumeAggregator agg = new VolumeAggregator(config)) {
            KafkaTradeConsumer kc = new KafkaTradeConsumer(consumer, agg, "t", new ObjectMapper());
            Thread t = new Thread(kc);
            t.start();
            Thread.sleep(150);
            kc.close();
            t.join(5_000);
            assertThat(agg.size()).isEqualTo(2);
        }
    }
}
