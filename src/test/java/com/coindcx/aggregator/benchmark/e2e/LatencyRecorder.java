package com.coindcx.aggregator.benchmark.e2e;

import java.util.Arrays;

/**
 * Collects nanosecond latency samples and computes percentile statistics.
 * Not thread-safe — intended for single-writer use during benchmark phases.
 */
final class LatencyRecorder {

    private long[] samples;
    private int count;

    LatencyRecorder(int expectedCapacity) {
        this.samples = new long[expectedCapacity];
        this.count = 0;
    }

    void record(long latencyNanos) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[count++] = latencyNanos;
    }

    int size() {
        return count;
    }

    LatencyStats compute() {
        if (count == 0) {
            return new LatencyStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);

        return new LatencyStats(
                count,
                sorted[0],
                percentile(sorted, 50),
                percentile(sorted, 90),
                percentile(sorted, 95),
                percentile(sorted, 99),
                percentile(sorted, 99.9),
                percentile(sorted, 99.99),
                sorted[sorted.length - 1]);
    }

    private static long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    record LatencyStats(
            int sampleCount,
            long minNs,
            long p50Ns,
            long p90Ns,
            long p95Ns,
            long p99Ns,
            long p999Ns,
            long p9999Ns,
            long maxNs) {

        String formatTable(String title) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("--- %s ---%n", title));
            sb.append(String.format("  Samples : %,d%n", sampleCount));
            sb.append(String.format("  %-8s: %s%n", "min", fmt(minNs)));
            sb.append(String.format("  %-8s: %s%n", "p50", fmt(p50Ns)));
            sb.append(String.format("  %-8s: %s%n", "p90", fmt(p90Ns)));
            sb.append(String.format("  %-8s: %s%n", "p95", fmt(p95Ns)));
            sb.append(String.format("  %-8s: %s%n", "p99", fmt(p99Ns)));
            sb.append(String.format("  %-8s: %s%n", "p99.9", fmt(p999Ns)));
            sb.append(String.format("  %-8s: %s%n", "p99.99", fmt(p9999Ns)));
            sb.append(String.format("  %-8s: %s%n", "max", fmt(maxNs)));
            return sb.toString();
        }

        private String fmt(long nanos) {
            if (nanos < 1_000) return String.format("%d ns", nanos);
            if (nanos < 1_000_000) return String.format("%.2f µs", nanos / 1_000.0);
            return String.format("%.3f ms", nanos / 1_000_000.0);
        }
    }
}
