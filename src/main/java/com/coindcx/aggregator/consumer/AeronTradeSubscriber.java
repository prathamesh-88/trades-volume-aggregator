package com.coindcx.aggregator.consumer;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to an Aeron channel and decodes trade events from DirectBuffers.
 *
 * Wire format (little-endian):
 * <pre>
 *   offset  0: userId      (long,   8 bytes)
 *   offset  8: volume      (double, 8 bytes)
 *   offset 16: timestampMs (long,   8 bytes)
 *   offset 24: symbolLen   (int,    4 bytes)
 *   offset 28: symbol      (UTF-8,  symbolLen bytes)
 * </pre>
 */
public final class AeronTradeSubscriber implements Runnable, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(AeronTradeSubscriber.class);

    private static final int USER_ID_OFFSET = 0;
    private static final int VOLUME_OFFSET = 8;
    private static final int TIMESTAMP_OFFSET = 16;
    private static final int SYMBOL_LEN_OFFSET = 24;
    private static final int SYMBOL_DATA_OFFSET = 28;

    private final Aeron aeron;
    private final Subscription subscription;
    private final VolumeAggregator aggregator;
    private final IdleStrategy idleStrategy;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AeronTradeSubscriber(Aeron aeron, AppConfig config, VolumeAggregator aggregator) {
        this.aeron = aeron;
        this.aggregator = aggregator;
        this.idleStrategy = new BackoffIdleStrategy(
                100, 10, 1_000, 1_000_000);

        String channel = config.aeronSubscriberChannel();
        int streamId = config.aeronSubscriberStreamId();
        this.subscription = aeron.addSubscription(channel, streamId);

        LOG.info("Aeron subscriber created on channel={} streamId={}", channel, streamId);
    }

    @Override
    public void run() {
        final FragmentHandler handler = this::onFragment;

        LOG.info("Aeron subscriber polling started");
        while (running.get()) {
            int fragmentsRead = subscription.poll(handler, 10);
            idleStrategy.idle(fragmentsRead);
        }

        subscription.close();
        LOG.info("Aeron subscriber closed");
    }

    private void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        try {
            long userId = buffer.getLong(offset + USER_ID_OFFSET);
            double volume = buffer.getDouble(offset + VOLUME_OFFSET);
            long timestampMs = buffer.getLong(offset + TIMESTAMP_OFFSET);
            int symbolLen = buffer.getInt(offset + SYMBOL_LEN_OFFSET);

            byte[] symbolBytes = new byte[symbolLen];
            buffer.getBytes(offset + SYMBOL_DATA_OFFSET, symbolBytes);
            String symbol = new String(symbolBytes, java.nio.charset.StandardCharsets.UTF_8);

            TradeEvent event = new TradeEvent(userId, symbol, volume, timestampMs);
            aggregator.addTrade(event);
        } catch (Exception e) {
            LOG.warn("Failed to decode Aeron trade fragment: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        LOG.info("Shutting down Aeron subscriber");
        running.set(false);
    }
}
