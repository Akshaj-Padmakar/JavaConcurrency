# Solution Notes

Last-day revision checklist:

- This is a turn-taking problem, not a parallel throughput problem. Only one thread should print each value.
- Shared state is `current`; every read and write of `current` must be protected by the same synchronization mechanism.
- For `Lock` and `Condition`, always wait inside a `while`, not an `if`, because wakeups can be spurious or caused by another signal.
- The condition is based on ownership of the current number: odd/even uses `current % 2`, and the generalized version uses `current % k`.
- After printing, increment `current` and signal the thread that owns the next number.
- On termination, wake another waiting thread before breaking; otherwise one thread may remain blocked forever.
- `solve()` should start all worker threads and then `join()` all of them.
- Restore interrupt status with `Thread.currentThread().interrupt()` when catching `InterruptedException`.

## Part 1: Lock and Condition

Use one `ReentrantLock`, one shared `current`, and two conditions:

- `oddCondition`
- `evenCondition`

Each thread loops:

1. Acquire lock.
2. Wait while `current <= n` and it is not this thread's turn.
3. If `current > n`, signal the other thread and exit.
4. Print `current`.
5. Increment `current`.
6. Signal the other condition.
7. Release lock in `finally`.

This pattern is easy to explain and generalizes to the K-thread version.

## Part 2: Semaphores

Use two semaphores:

- Even semaphore starts with `1` permit because `0` is even.
- Odd semaphore starts with `0` permits.

Each thread:

1. Acquires its own semaphore.
2. If `current > n`, releases the other semaphore and exits.
3. Prints `current`.
4. Increments `current`.
5. Releases the other semaphore.

The semaphore permits encode whose turn it is.

## Part 3: K Threads

Use:

```java
List<Condition> conditions
```

where `conditions.get(i)` belongs to thread `i`.

Thread `i` waits while:

```java
current <= n && current % k != i
```

After printing, it signals:

```java
conditions.get((i + 1) % k).signal();
```

The key interview invariant is:

```text
current % k determines the only thread allowed to print current.
```

## Current Code Review Notes

- The overall approach is correct: lock/condition for turn-taking, semaphore variant for two-thread handoff, and condition-list variant for K-thread handoff.
- The current implementations start from `0`, so the even thread should print first.
- The current code uses `printStackTrace()` on interruption; cleaner interview code should restore interrupt status and exit the worker loop.
- In the lock/condition versions, termination should signal the next waiting thread before breaking.
- For invalid input such as `k <= 0`, the K-thread version should reject the input early.
