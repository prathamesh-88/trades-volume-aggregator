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

public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        LOG.info("Starting Trade Volume Aggregator");

        AppConfig config = new AppConfig();

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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook triggered");
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
        }, "shutdown-hook"));

        kafkaThread.start();
        aeronSubThread.start();
        snapshotEmitter.start();

        LOG.info("Trade Volume Aggregator is running");

        kafkaThread.join();
        aeronSubThread.join();
    }
}
