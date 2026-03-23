package com.coindcx.aggregator;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.consumer.AeronTradeSubscriber;
import com.coindcx.aggregator.consumer.KafkaTradeConsumer;
import com.coindcx.aggregator.publisher.AeronSnapshotPublisher;
import com.coindcx.aggregator.publisher.KafkaSnapshotPublisher;
import com.coindcx.aggregator.snapshot.SnapshotEmitter;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires and runs the aggregator runtime (embedded driver, consumers, snapshot emitter).
 * Package-private so tests in the same package can start and shut down the stack.
 */
final class AggregatedServices implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(AggregatedServices.class);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final VolumeAggregator aggregator;
    private final KafkaTradeConsumer kafkaConsumer;
    private final Thread kafkaThread;
    private final AeronTradeSubscriber aeronSubscriber;
    private final Thread aeronSubThread;
    private final KafkaSnapshotPublisher kafkaPublisher;
    private final AeronSnapshotPublisher aeronPublisher;
    private final SnapshotEmitter snapshotEmitter;

    private AggregatedServices(
            MediaDriver mediaDriver,
            Aeron aeron,
            VolumeAggregator aggregator,
            KafkaTradeConsumer kafkaConsumer,
            Thread kafkaThread,
            AeronTradeSubscriber aeronSubscriber,
            Thread aeronSubThread,
            KafkaSnapshotPublisher kafkaPublisher,
            AeronSnapshotPublisher aeronPublisher,
            SnapshotEmitter snapshotEmitter) {
        this.mediaDriver = mediaDriver;
        this.aeron = aeron;
        this.aggregator = aggregator;
        this.kafkaConsumer = kafkaConsumer;
        this.kafkaThread = kafkaThread;
        this.aeronSubscriber = aeronSubscriber;
        this.aeronSubThread = aeronSubThread;
        this.kafkaPublisher = kafkaPublisher;
        this.aeronPublisher = aeronPublisher;
        this.snapshotEmitter = snapshotEmitter;
    }

    static AggregatedServices start(AppConfig config) throws IOException {
        LOG.info("Starting Trade Volume Aggregator");

        MediaDriver mediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context().aeronDirectoryName(config.aeronDirectory()));
        LOG.info("Embedded Aeron MediaDriver started at {}", mediaDriver.aeronDirectoryName());

        Aeron aeron = Aeron.connect(
                new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));

        VolumeAggregator aggregator = new VolumeAggregator(config);

        KafkaTradeConsumer kafkaConsumer = new KafkaTradeConsumer(config, aggregator);
        Thread kafkaThread = new Thread(kafkaConsumer, "kafka-consumer");
        kafkaThread.setDaemon(true);

        AeronTradeSubscriber aeronSubscriber = new AeronTradeSubscriber(aeron, config, aggregator);
        Thread aeronSubThread = new Thread(aeronSubscriber, "aeron-subscriber");
        aeronSubThread.setDaemon(true);

        KafkaSnapshotPublisher kafkaPublisher = new KafkaSnapshotPublisher(config);
        AeronSnapshotPublisher aeronPublisher = new AeronSnapshotPublisher(aeron, config);
        SnapshotEmitter snapshotEmitter = new SnapshotEmitter(config, aggregator, kafkaPublisher, aeronPublisher);

        return new AggregatedServices(
                mediaDriver,
                aeron,
                aggregator,
                kafkaConsumer,
                kafkaThread,
                aeronSubscriber,
                aeronSubThread,
                kafkaPublisher,
                aeronPublisher,
                snapshotEmitter);
    }

    void startBackground() {
        kafkaThread.start();
        aeronSubThread.start();
        snapshotEmitter.start();
        LOG.info("Trade Volume Aggregator is running");
    }

    void await() throws InterruptedException {
        kafkaThread.join();
        aeronSubThread.join();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutdown triggered");
        snapshotEmitter.close();
        kafkaConsumer.close();
        aeronSubscriber.close();

        try {
            kafkaThread.join(5_000);
            aeronSubThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        kafkaPublisher.close();
        aeronPublisher.close();
        aggregator.close();
        aeron.close();
        mediaDriver.close();
        LOG.info("Trade Volume Aggregator stopped");
    }
}
