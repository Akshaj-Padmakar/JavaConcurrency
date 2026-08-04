# Read-Write Lock (Readers–Writers)

> Book ref: _The Little Book of Semaphores_ §4.2 — **Readers–Writers**.

## Problem

Multiple threads share a resource. Design a lock that allows:

- **Many readers** to access it **at the same time** (reads don't conflict).
- Only **one writer** at a time, with **no readers present** (writes are exclusive).

A plain mutex would work but needlessly serializes concurrent reads. A read-write lock keeps
reads parallel while keeping writes exclusive.

### API

```java
void readLock()  throws InterruptedException;
void readUnlock();
void writeLock() throws InterruptedException;
void writeUnlock();
```

### Invariant

At any moment: **many readers and no writer**, _or_ **one writer and no readers**. Never both.

## Points to Ponder

- **Preference policy.** Reader-preference, writer-preference, or fair (FIFO)? Each is correct;
  they differ only in _who starves_. Decide and document one.
- **Starvation.** Reader-preference starves writers (readers keep arriving, count never hits 0);
  writer-preference starves readers. How would you make it fair / no-starve?
- **Reentrancy.** Can a thread that holds the read lock take it again? Why do you need a per-thread
  read count, not a single `int`?
- **Upgrade vs downgrade.** Downgrade (write → read) is safe. Upgrade (read → write) can deadlock
  if two readers try it at once — why? What does `ReentrantReadWriteLock` do about it?
- **`notify()` vs `notifyAll()`.** Readers and writers wait on the same monitor for _different_
  predicates. What breaks with `notify()`?
- **JDK equivalents.** `ReentrantReadWriteLock` (fairness, downgrade, no upgrade) and `StampedLock`
  (optimistic reads). When would you reach for optimistic reads?
- **Testing.** How do you prove a writer never overlaps a reader? (Shared counter + invariant check
  under stress.)
