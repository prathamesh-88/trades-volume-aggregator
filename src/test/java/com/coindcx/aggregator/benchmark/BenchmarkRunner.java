package com.coindcx.aggregator.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single entry point to run all NFR-related benchmarks and emit a JSON report.
 *
 * Usage:
 *   java -cp target/test-classes:target/classes:target/dependency/* \
 *        com.coindcx.aggregator.benchmark.BenchmarkRunner
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        Options opts = new OptionsBuilder()
                .include("com\\.coindcx\\.aggregator\\.benchmark\\..*")
                .resultFormat(ResultFormatType.JSON)
                .result("target/jmh-results.json")
                .build();

        new Runner(opts).run();
    }
}
