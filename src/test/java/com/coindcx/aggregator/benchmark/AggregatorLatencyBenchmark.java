package com.coindcx.aggregator.benchmark;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import org.openjdk.jmh.annotations.*;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures per-operation latency of VolumeAggregator.addTrade()
 * including average and percentile distribution.
 *
 * NFR target: P99 < 10ms per trade event processing.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED"
})
public class AggregatorLatencyBenchmark {

    private VolumeAggregator aggregator;
    private File mapFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        mapFile = File.createTempFile("bench-latency-", ".dat");
        mapFile.deleteOnExit();

        Properties overrides = new Properties();
        overrides.setProperty("chronicle.map.entries", "200000");
        overrides.setProperty("chronicle.map.file", mapFile.getAbsolutePath());
        AppConfig config = new AppConfig(overrides);

        aggregator = new VolumeAggregator(config);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 50_000; i++) {
            aggregator.addTrade(new TradeEvent(
                    rng.nextLong(1, 10001), "BTC/USDT",
                    rng.nextDouble(0.001, 10.0), System.currentTimeMillis()));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        aggregator.close();
        mapFile.delete();
    }

    @Benchmark
    public void addTrade_singleUser() {
        aggregator.addTrade(new TradeEvent(42L, "BTC/USDT", 1.5, System.currentTimeMillis()));
    }

    @Benchmark
    public void addTrade_randomUser() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long userId = rng.nextLong(1, 10001);
        aggregator.addTrade(new TradeEvent(userId, "BTC/USDT", rng.nextDouble(0.001, 10.0), System.currentTimeMillis()));
    }
}
