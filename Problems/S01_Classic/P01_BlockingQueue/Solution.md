# Blocking Queue — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §4.1 — **Producer–Consumer**.
> A bounded blocking queue **is** the producer–consumer problem, packaged as a reusable data structure.

## The one-line idea

A bounded FIFO buffer where **producers block when full** and **consumers block when empty**.
Guard the buffer with one `Lock`; use **two `Condition`s** so you wake only the side that can make progress.

## Core template (memorize this)

```java
private final Lock lock = new ReentrantLock(fair);
private final Condition notFull  = lock.newCondition(); // producers wait here
private final Condition notEmpty = lock.newCondition(); // consumers wait here

void put(T x) throws InterruptedException {
    lock.lock();
    try {
        while (size == capacity) notFull.await();   // WHILE, not if
        enqueue(x);
        notEmpty.signal();                          // wake ONE consumer
    } finally { lock.unlock(); }
}

T take() throws InterruptedException {
    lock.lock();
    try {
        while (size == 0) notEmpty.await();
        T x = dequeue();
        notFull.signal();                           // wake ONE producer
        return x;
    } finally { lock.unlock(); }
}
```

## Four things an interviewer is checking

1. **`while`, never `if`, around `await()`** — protects against _spurious wakeups_ and
   the "another thread grabbed the slot before I re-acquired the lock" race.
2. **Two conditions, not one** — `notEmpty.signal()` targets only consumers, `notFull.signal()`
   only producers. With a _single_ condition you'd be forced to `signalAll()` (thundering herd)
   to avoid waking the wrong side and deadlocking.
3. **`signal()` vs `signalAll()`** — `signal()` is correct _and_ cheaper here because every
   waiter on a given condition is waiting for the _same_ predicate, and each op frees exactly
   one slot. Use `signalAll()` only when waiters wait on _different_ predicates on one condition.
4. **`lock()` outside `try`, `unlock()` in `finally`** — always.

## The full API (what each method does when full/empty)

| Method                     | Full (insert)          | Empty (remove)     | Blocks? |
| -------------------------- | ---------------------- | ------------------ | ------- |
| `add` / `remove`           | throws `IllegalState`  | throws / returns   | no      |
| `offer` / `poll`           | returns `false`/`null` | returns `null`     | no      |
| `put` / `take`             | wait forever           | wait forever       | yes     |
| `offer/poll(timeout,unit)` | wait up to timeout     | wait up to timeout | timed   |

Mnemonic: **add=throw, offer=boolean/null, put/take=block, timed=deadline**.

## Timed wait — the deadline pattern (easy to get wrong)

```java
long deadline = System.nanoTime() + unit.toNanos(timeout);
while (size == capacity) {
    long nanos = deadline - System.nanoTime();      // recompute EACH loop
    if (!notFull.await(nanos, TimeUnit.NANOSECONDS)) // false == timed out
        return false;
}
```

Key point: recompute remaining time every iteration (spurious wakeups must not reset the clock).
`await(nanos)` returning `false` means the deadline passed.

## Gotchas / edge cases to state out loud

- **Reject `null`** on every insert path (a null element collides with `poll()`'s "null = empty").
- **Interrupt-vs-signal hazard**: with `signal()`, a waiter can be signaled _and_ interrupted at
  once — it throws `InterruptedException` but "ate" the signal, stalling other waiters. Mitigate by
  re-signalling in the catch before rethrowing, or by using `signalAll()`.
- **Validate `capacity > 0`** in the constructor.
- Prefer **`ArrayDeque`** over `LinkedList` for the buffer (no per-node allocation, better locality).
- Keep a single source of truth for size (either a counter _or_ `queue.size()`, not both drifting).

## Complexity

- All ops: **O(1)** time. Space **O(capacity)**.
- Contention is serialized by one lock — fine for a queue; if profiling shows the lock is hot,
  the next step is a two-lock (head/tail) design like `LinkedBlockingQueue`, or lock-free
  (`ConcurrentLinkedQueue`) if unbounded is acceptable.

## 30-second recall

> One lock, two conditions. Producers `while(full) await(notFull)`; consumers
> `while(empty) await(notEmpty)`. Each op signals the _other_ condition once.
> `while`-guard every wait. Reject nulls. Timed = recompute deadline each loop.

## The interrupt-vs-signal hazard (senior-level talking point)

This is the subtle one. With signal() (not signalAll()), if a waiting consumer is signaled and interrupted at nearly the same moment, await() may throw InterruptedException after consuming the signal — so the item sits in the queue but no other consumer was woken. That element stalls until the next put().
