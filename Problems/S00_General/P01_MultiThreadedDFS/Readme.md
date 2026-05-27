# MultiThreadedDFS Notes

## Core Idea

Parallel graph traversal using:

- `ExecutorService` → task execution
- `AtomicInteger` → active task reference counting
- `CountDownLatch` → completion signaling
- `synchronized(vis)` → atomic visited check + mark

This is not strict DFS ordering. It is **parallel graph exploration with DFS-style recursion**.

---

## Design Pattern: Reference Counting

Problem:

Recursive tasks dynamically submit more tasks.

Simple:

```java
shutdown();
awaitTermination();
```

is insufficient because child tasks are not known upfront.

Solution:

Use **reference counting**.

### Rule

Before submitting a task:

```java
activeTasks.incrementAndGet();
```

When task finishes:

```java
activeTasks.decrementAndGet();
```

Completion condition:

```java
activeTasks == 0
```

Then:

```java
completionLatch.countDown();
```

This pattern is useful for:

- Recursive parallel algorithms
- Dynamic task graphs
- Tree / graph processing
- Async fan-out workloads

---

## API:

```java
multiThreadedDFS(startNode)
```

---

## Thread Pool Sizing

### 1. CPU-bound work

Example:

- Computation
- Parsing
- Algorithms
- No blocking

Use:

```text
threads ≈ CPU cores
```

**Reason:**

Extra threads:

- context switching
- cache contention
- no throughput gain

---

### 2. I/O / Blocking work

Example:

- Sleep
- Network
- DB
- Disk

Approximation:

```text
Threads ≈ Cores × (1 + Wait / Compute)
```

Example:

8 cores

- wait = 200ms
- compute = 20ms

Then:

```text
8 × (1 + 10)
≈ 88 threads
```

Reason:

Blocked threads do not consume CPU.

---

## Graph Structure Matters

Thread count is also bounded by available graph parallelism.

### Chain

```text
1 -> 2 -> 3
```

Parallelism:

```text
1
```

Extra threads useless.

### Wide graph

```text
      1
    / | \
   2  3  4
```

Higher concurrency.

Useful threads roughly bounded by:

- branching factor
- frontier width

---

## Practical Rule

For unknown workloads:

CPU-heavy:

```text
threads = cores
```

Blocking / mixed:

```text
2×–4× cores
```

Then benchmark.

Thread count should be driven by:

- work type
- blocking ratio
- graph parallelism
- contention
