package com.coindcx.aggregator.publisher;

import com.coindcx.aggregator.model.VolumeSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaSnapshotPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishSerialisesSnapshotsAndFlushes() throws Exception {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        ObjectMapper mapper = new ObjectMapper();
        KafkaSnapshotPublisher pub = new KafkaSnapshotPublisher(producer, "snap-topic", mapper);

        VolumeSnapshot s = new VolumeSnapshot(3L, 9.5, 1L, 2L);
        pub.publish(List.of(s));

        ArgumentCaptor<ProducerRecord<String, String>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(cap.capture(), any());
        verify(producer).flush();

        ProducerRecord<String, String> rec = cap.getValue();
        assertThat(rec.topic()).isEqualTo("snap-topic");
        assertThat(rec.key()).isEqualTo("3");
        VolumeSnapshot read = mapper.readValue(rec.value(), VolumeSnapshot.class);
        assertThat(read.getUserId()).isEqualTo(3L);
        assertThat(read.getTotalVolume()).isEqualTo(9.5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serialiseFailureIsSkippedAndFlushStillRuns() throws Exception {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});

        KafkaSnapshotPublisher pub = new KafkaSnapshotPublisher(producer, "t", mapper);
        pub.publish(List.of(new VolumeSnapshot(1L, 1.0, 1L, 1L)));

        verify(producer).flush();
        verify(producer, never()).send(any(ProducerRecord.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendSuccessCallbackWithNullException() throws Exception {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        ObjectMapper mapper = new ObjectMapper();
        RecordMetadata metadata = mock(RecordMetadata.class);

        doAnswer(inv -> {
            org.apache.kafka.clients.producer.Callback cb = inv.getArgument(1);
            cb.onCompletion(metadata, null);
            return mock(Future.class);
        }).when(producer).send(any(ProducerRecord.class), any());

        KafkaSnapshotPublisher pub = new KafkaSnapshotPublisher(producer, "t", mapper);
        pub.publish(List.of(new VolumeSnapshot(9L, 1.0, 1L, 1L)));

        verify(producer).flush();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendFailureCallbackIsHandled() throws Exception {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        ObjectMapper mapper = new ObjectMapper();

        doAnswer(inv -> {
            org.apache.kafka.clients.producer.Callback cb = inv.getArgument(1);
            cb.onCompletion(null, new RuntimeException("broker down"));
            return mock(Future.class);
        }).when(producer).send(any(ProducerRecord.class), any());

        KafkaSnapshotPublisher pub = new KafkaSnapshotPublisher(producer, "t", mapper);
        pub.publish(List.of(new VolumeSnapshot(2L, 1.0, 1L, 1L)));

        verify(producer).flush();
    }

    @Test
    void closeDelegatesToProducer() {
        @SuppressWarnings("unchecked")
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        KafkaSnapshotPublisher pub = new KafkaSnapshotPublisher(producer, "t", new ObjectMapper());
        pub.close();
        verify(producer).close();
    }
}
