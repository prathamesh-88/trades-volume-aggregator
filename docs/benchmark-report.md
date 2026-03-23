# Benchmark Report — Trade Volume Aggregator

**Date:** 2026-03-23  
**Tool:** JMH 1.37 (Java Microbenchmark Harness)  
**JDK:** OpenJDK 25.0.2, 64-Bit Server VM (Apple Silicon, 12 cores)  
**Machine:** macOS, Apple M-series (ARM64)

---

## NFR Targets

| NFR | Target |
|-----|--------|
| Latency | P99 < 10 ms |
| Throughput (avg) | 25,000 RPS |
| Throughput (peak) | 100,000 RPS |

---

## 1. Aggregation Throughput (Single-Threaded)

Measures `VolumeAggregator.addTrade()` — the core Chronicle Map hot path.

| User Cardinality | Throughput (ops/s) | ± Error | vs 100K RPS Target |
|------------------|--------------------|---------|---------------------|
| 1,000 | **334,607** | ± 10,826 | 3.3x headroom |
| 10,000 | **298,235** | ± 3,214 | 3.0x headroom |
| 100,000 | **289,125** | ± 7,363 | 2.9x headroom |

**Verdict: PASS.** A single thread sustains ~290K–335K ops/s, exceeding the 100K RPS peak target by ~3x. Throughput degrades gracefully (~14%) even at 100x the user cardinality.

---

## 2. Concurrent Aggregation Throughput (12 Threads)

Measures multi-threaded `addTrade()` with Chronicle Map's segment-level locking.

| Contention Level | Throughput (ops/s) | ± Error | vs 100K RPS Target |
|------------------|--------------------|---------|---------------------|
| High (100 users) | **583,864** | ± 43,021 | 5.8x headroom |
| Low (100K users) | **573,672** | ± 166,059 | 5.7x headroom |

**Verdict: PASS.** Under full concurrency (12 threads), aggregate throughput reaches ~580K ops/s — nearly 6x the peak target. Segment-level locking effectively eliminates contention.

---

## 3. Aggregation Latency (Per-Operation)

Measures `addTrade()` latency distribution using JMH SampleTime mode.

### Random User (10K cardinality, pre-warmed)

| Percentile | Latency (µs) | Latency (ms) | vs 10ms P99 Target |
|------------|---------------|---------------|---------------------|
| p50 | 3.332 | 0.003 | 3,000x headroom |
| p90 | 3.624 | 0.004 | 2,762x headroom |
| p95 | 3.792 | 0.004 | 2,637x headroom |
| **p99** | **6.536** | **0.007** | **1,530x headroom** |
| p99.9 | 59.520 | 0.060 | 168x headroom |
| p99.99 | 393.865 | 0.394 | 25x headroom |
| p100 (max) | 2,084.864 | 2.085 | 4.8x headroom |

### Single User (hot key, worst-case contention)

| Percentile | Latency (µs) | Latency (ms) | vs 10ms P99 Target |
|------------|---------------|---------------|---------------------|
| p50 | 3.164 | 0.003 | 3,162x headroom |
| p90 | 3.456 | 0.003 | 2,894x headroom |
| p95 | 3.580 | 0.004 | 2,793x headroom |
| **p99** | **6.624** | **0.007** | **1,510x headroom** |
| p99.9 | 60.744 | 0.061 | 165x headroom |
| p99.99 | 437.352 | 0.437 | 23x headroom |
| p100 (max) | 1,058.816 | 1.059 | 9.4x headroom |

**Verdict: PASS.** P99 latency is ~6.5 µs (0.007 ms) — over **1,500x below** the 10ms target. Even the absolute worst case (p100) stays below 2.1ms.

---

## 4. JSON Deserialization Throughput (Kafka Path)

Measures Jackson `ObjectMapper.readValue()` for `TradeEvent` — the Kafka consumer bottleneck.

| Payload | Throughput (ops/s) | ± Error |
|---------|--------------------|---------|
| Typical (`BTC/USDT`) | **4,847,625** | ± 96,504 |
| Large symbol | **4,157,831** | ± 251,152 |

### Deserialization Latency

| Percentile | Latency (µs) |
|------------|---------------|
| p50 | 0.208 |
| p90 | 0.250 |
| p99 | 0.292 |
| p99.9 | 5.670 |

**Verdict: PASS.** JSON deserialization sustains ~4.8M ops/s — 48x above the peak RPS target. At P99 = 0.3µs, deserialization adds negligible latency to the pipeline.

---

## 5. Snapshot Read Under Concurrent Writes

Measures `getSnapshots()` latency while a background writer continuously calls `addTrade()`.

| Preloaded Users | Avg (ms) | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | P99.9 (ms) | P100 (ms) |
|-----------------|----------|----------|----------|----------|----------|------------|-----------|
| 1,000 | 2.107 | 2.048 | 2.265 | 2.433 | 2.638 | 5.595 | 9.617 |
| 10,000 | 16.638 | 16.433 | 16.941 | 17.197 | 24.651 | 42.533 | 42.533 |
| 50,000 | 76.542 | 75.629 | 77.608 | 80.236 | 109.904 | 116.392 | 116.392 |

**Verdict: PASS for typical workloads.** At 1K users, snapshot reads complete in ~2ms (P99 = 2.6ms), well within the 1-second emission interval. At higher user counts, snapshot read time scales linearly — this is expected since `getSnapshots()` copies all entries. For > 10K users, consider paginated or incremental snapshot strategies.

---

## Summary — NFR Compliance

| NFR | Target | Measured | Status |
|-----|--------|----------|--------|
| P99 Latency (addTrade) | < 10 ms | **0.007 ms** (6.5 µs) | **PASS** (1,500x margin) |
| Avg Throughput | 25K RPS | **298K ops/s** (single-thread) | **PASS** (12x margin) |
| Peak Throughput | 100K RPS | **584K ops/s** (12-thread) | **PASS** (5.8x margin) |
| JSON Deserialization | not bottleneck | **4.8M ops/s** | **PASS** (48x margin) |
| Snapshot Read (1K users) | < 1s interval | **2.1ms avg** | **PASS** |

---

## How to Reproduce

```bash
# Build
mvn clean test-compile

# Copy dependencies
mvn dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/dependency

# Run all benchmarks
java -cp "target/test-classes:target/classes:target/dependency/*" \
    com.coindcx.aggregator.benchmark.BenchmarkRunner

# JSON results saved to target/jmh-results.json
```

---

## Caveats

1. **Microbenchmark only** — These measure component-level performance in isolation. Real end-to-end latency includes Kafka poll overhead, network I/O, and GC pauses.
2. **No GC pressure test** — A dedicated GC benchmark under sustained load (e.g., 30-minute soak test) would provide additional confidence.
3. **Single-node** — Benchmarks run on a single machine; production distributed deployment may introduce network-related latency.
4. **Snapshot scaling** — `getSnapshots()` copies all entries; for very large user populations (>50K), consider streaming or delta-based emission.
