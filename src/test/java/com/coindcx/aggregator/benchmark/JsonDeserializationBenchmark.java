package com.coindcx.aggregator.benchmark;

import com.coindcx.aggregator.model.TradeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Measures Jackson JSON deserialization throughput and latency for TradeEvent —
 * the bottleneck on the Kafka consumer path before aggregation.
 *
 * NFR target: deserialization alone must sustain >= 100K ops/s.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class JsonDeserializationBenchmark {

    private ObjectMapper objectMapper;

    private String smallPayload;
    private String largeSymbolPayload;

    @Setup(Level.Trial)
    public void setup() {
        objectMapper = new ObjectMapper();
        smallPayload = "{\"userId\":12345,\"symbol\":\"BTC/USDT\",\"volume\":2.5,\"timestampMs\":1700000000000}";
        largeSymbolPayload = "{\"userId\":99999,\"symbol\":\"SUPERVERYLONGTOKENNAME/ANOTHERLONGTOKEN\"," +
                "\"volume\":0.00012345,\"timestampMs\":1700000000000}";
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public TradeEvent deserialize_typical() throws IOException {
        return objectMapper.readValue(smallPayload, TradeEvent.class);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public TradeEvent deserialize_largeSymbol() throws IOException {
        return objectMapper.readValue(largeSymbolPayload, TradeEvent.class);
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public TradeEvent deserialize_latency() throws IOException {
        return objectMapper.readValue(smallPayload, TradeEvent.class);
    }
}
