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
 * Measures multi-threaded throughput of VolumeAggregator.addTrade()
 * to validate the system under concurrent producer load.
 *
 * Chronicle Map uses segment-level locking, so throughput should scale
 * with thread count up to the number of segments.
 *
 * NFR target: aggregate throughput >= 100K ops/s across all threads.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
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
@Threads(Threads.MAX)
public class ConcurrentAggregatorBenchmark {

    private VolumeAggregator aggregator;
    private File mapFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        mapFile = File.createTempFile("bench-concurrent-", ".dat");
        mapFile.deleteOnExit();

        Properties overrides = new Properties();
        overrides.setProperty("chronicle.map.entries", "200000");
        overrides.setProperty("chronicle.map.file", mapFile.getAbsolutePath());
        AppConfig config = new AppConfig(overrides);

        aggregator = new VolumeAggregator(config);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        aggregator.close();
        mapFile.delete();
    }

    @Benchmark
    public void addTrade_highContention() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long userId = rng.nextLong(1, 101);
        aggregator.addTrade(new TradeEvent(userId, "BTC/USDT", rng.nextDouble(0.001, 10.0), System.currentTimeMillis()));
    }

    @Benchmark
    public void addTrade_lowContention() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long userId = rng.nextLong(1, 100001);
        aggregator.addTrade(new TradeEvent(userId, "ETH/USDT", rng.nextDouble(0.001, 10.0), System.currentTimeMillis()));
    }
}
