# Aeron

## What is Aeron?



## How does it work?

###  Media Driver




## Aeron v/s Kafka

| Feature     | Apache Kafka                                                    | Aeron                                                            |
| ----------- | --------------------------------------------------------------- | ---------------------------------------------------------------- |
| Architecture | Broker-based: Centralized servers (Brokers) manage the data.   | Brokerless: Peer-to-peer; data moves directly between processes. |
| Latency     | Milliseconds: Usually 5ms–50ms.                                 | Microseconds: Usually 5µs–100µs.                                 |
| Persistence | Built-in: Data is written to disk by default and can be replayed. | Optional: Use Aeron Archive if you need to save and replay data. |
| Protocol    | TCP: Reliable but has "chatter" and head-of-line blocking.      | UDP + Shared Memory: Reliable UDP for network; IPC for same-machine. |
| Complexity  | High (requires Zookeeper/KRaft, JVM tuning).                    | High (requires low-level networking and CPU knowledge).           |