package com.coindcx.aggregator.benchmark;

import com.coindcx.aggregator.aggregator.VolumeAggregator;
import com.coindcx.aggregator.config.AppConfig;
import com.coindcx.aggregator.model.TradeEvent;
import com.coindcx.aggregator.model.VolumeSnapshot;
import org.openjdk.jmh.annotations.*;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures getSnapshots() latency while concurrent writes are happening.
 * This simulates the SnapshotEmitter reading all aggregates
 * while Kafka/Aeron consumers are writing.
 *
 * NFR target: snapshot read should complete well within the 1s emission interval.
 */
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
public class SnapshotReadBenchmark {

    private VolumeAggregator aggregator;
    private File mapFile;
    private volatile boolean writerRunning;
    private Thread writerThread;

    @Param({"1000", "10000", "50000"})
    private int preloadedUsers;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        mapFile = File.createTempFile("bench-snapshot-", ".dat");
        mapFile.deleteOnExit();

        Properties overrides = new Properties();
        overrides.setProperty("chronicle.map.entries", "200000");
        overrides.setProperty("chronicle.map.file", mapFile.getAbsolutePath());
        AppConfig config = new AppConfig(overrides);

        aggregator = new VolumeAggregator(config);

        for (int i = 1; i <= preloadedUsers; i++) {
            aggregator.addTrade(new TradeEvent(i, "BTC/USDT", 1.0, System.currentTimeMillis()));
        }

        writerRunning = true;
        writerThread = new Thread(() -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            while (writerRunning) {
                long userId = rng.nextLong(1, preloadedUsers + 1);
                aggregator.addTrade(new TradeEvent(userId, "ETH/USDT", rng.nextDouble(0.001, 5.0), System.currentTimeMillis()));
            }
        }, "background-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        writerRunning = false;
        writerThread.join(2000);
        aggregator.close();
        mapFile.delete();
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Collection<VolumeSnapshot> getSnapshots() {
        return aggregator.getSnapshots();
    }
}
