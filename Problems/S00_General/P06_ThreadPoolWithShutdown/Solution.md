# Thread Pool With Shutdown — Revision Sheet

> Interview form. See [Problem.md](Problem.md). Implementation: `MyBlockingQueue.java` (bounded
> blocking queue, `Lock` + two `Condition`s), `MyThreadPool.java` (the pool itself),
> `RejectedExecutionException.java`, `Test/MyThreadPoolTest.java`.

## One-line idea

Workers only ever do one thing in a loop: `take()` from the queue, run it. Graceful shutdown is
implemented as a **poison pill** — a sentinel `Runnable` enqueued once per worker — instead of a
"should I stop?" flag, because a worker blocked in `take()` can't poll a flag without either
busy-waiting or adding a timeout that's either too short (wastes CPU) or too long (delays
shutdown). Since the queue is strict FIFO, every real task queued before the pills is guaranteed
to be drained and run before any worker reaches its pill and exits — no separate "is the queue
really empty AND are we really done" check needed.

## State model

| Field                          | Meaning                                                                     |
| ------------------------------ | --------------------------------------------------------------------------- |
| `taskQueue`                    | `MyBlockingQueue<Runnable>` — bounded, gives `execute()` real backpressure   |
| `workers`                      | fixed list of `Thread`s, started in the constructor, never resized          |
| `poolSize`                     | number of workers — and therefore the exact number of poison pills to enqueue |
| `isShutdown` / `isShutdownNow` | `volatile` status flags; **written** under `lock`, **read** lock-free        |
| `lock`                         | one `ReentrantLock` guarding the shutdown/submit race — see below           |

## The race that actually matters (and why `lock` isn't optional)

`execute()` does "check `isShutdown`, then `put()`" — two steps. `shutdown()` does "set
`isShutdown`, then enqueue `poolSize` poison pills" — also multiple steps. Interleaved naively,
a task could land in the queue **after** some (not all) poison pills: whichever worker dequeues
that task's poison pill exits immediately without draining what comes after it, so if all workers
happen to reach their pills before any of them reaches that straggling real task, it's silently
stranded forever — `shutdown()` returns having "succeeded," the caller has no idea a task never ran.

Fix: `execute()`'s check-and-put and `shutdown()`'s flag-set-and-pill-enqueue are each done
**entirely** inside the same `lock`. That guarantees every `execute()` call is either fully ordered
before the whole shutdown-initiation (task is queued strictly before all pills → guaranteed to run)
or fully after it (sees `isShutdown == true` → rejected). No interleaving is possible.

The general rule this is an instance of: **a lock protects an invariant, not a field.** The
invariant here isn't "`isShutdown` has a coherent value" — it's *"if a task is in the queue, it sits
ahead of every poison pill."* Guarding only the flag read would give a correct read of the flag and
then let the `put()` land anywhere. The queue's own lock doesn't help either: it makes the `put()`
atomic, but atomically appending **after** the pills is exactly the bug. When a decision is made
under a lock, the action that decision authorizes has to happen under the same lock, or the
decision was just a stale observation.

**Cost of this fix:** it fully serializes concurrent `execute()` calls against each other (only one
caller can be inside the lock at a time, even if the queue has room for both) — a throughput hit
under many concurrent submitters, not a correctness one. Real
`java.util.concurrent.ThreadPoolExecutor` avoids exactly this by packing running-state and
worker-count into one `AtomicInteger` (`ctl`) manipulated with CAS instead of a single coarse lock
— worth knowing that trade-off exists even though this implementation takes the simpler, safer
route. Verified this doesn't actually break anything, at least:
`testConcurrentSubmittersDontLoseOrCorruptTasks` runs 8 threads submitting 25 tasks each
(200 total) concurrently against the same pool.

## `volatile` vs. reading under the lock — and the deadlock that settled it

Both flags are `volatile`, written under `lock`, read lock-free. That split is deliberate:

> **Lock** when the read must be atomic with something else — a decision that authorizes a
> subsequent action, or an invariant spanning multiple pieces of state.
> **`volatile`** when you only need one variable's value to be visible and fresh.

| Read site                                   | Compound?                    | Mechanism  |
| ------------------------------------------- | ---------------------------- | ---------- |
| `execute()` — flag, then `put()`            | yes, the read authorizes the put | `lock`     |
| `shutdown()` — flag, then pill batch        | yes                          | `lock`     |
| `shutdownNow()` — flag flip                 | yes, it fences off submission | `lock`     |
| worker loop — "should I stop?"              | no, standalone read          | `volatile` |
| `isShutdown()` / `isShutdownNow()`          | no, standalone read          | `volatile` |
| `isTerminated()`                            | no — inherently a snapshot   | `volatile` |

An earlier revision took `lock` inside the worker loop instead of using `volatile`, and it
**deadlocked on plain task submission** — no shutdown involved:

1. Each of the 4 workers calls `take()`, gets an item, then blocks acquiring `lock`.
2. The submitter fills the capacity-3 queue and blocks inside `put()` — *while holding `lock`*.
3. The submitter can only proceed once a worker calls `take()` to free a slot.
4. No worker can reach `take()` again — all of them are parked waiting for `lock`.

Three reasons `volatile` is right for the standalone reads:

- **It can't deadlock.** A `volatile` read is a load with an ordering constraint — no acquisition,
  no waiting, no new edge in the lock-order graph. Locks compose badly; every extra acquisition
  site is another chance to close a cycle, which is precisely what happened above.
- **The lock wasn't buying atomicity anyway.** `isTerminated()` reads the flag and *then* scans
  `isAlive()`; locking only the flag read leaves the scan uncovered. That's "coherent read, then act
  on stale info" — you pay for the lock and get only visibility, which is what `volatile` already
  gives. `isTerminated()` is inherently a point-in-time snapshot that can go stale the instant it
  returns; there is no compound operation there worth protecting.
- **Cost.** The worker's check runs once per task.

The two mechanisms are complementary, not alternatives: the lock gives writers atomicity across
compound operations, `volatile` gives lock-free readers a legal, fresh read.

## `shutdownNow()`: drain outside the lock, drain *before* interrupting

```java
lock.lock();
try { isShutdown = true; isShutdownNow = true; } finally { lock.unlock(); }
List<Runnable> neverStarted = taskQueue.drainAll();
for (Thread t : workers) t.interrupt();
return neverStarted;
```

**Why the drain can sit outside the lock.** Because `execute()` holds `lock` across *both* its check
and its `put()`, any submitter that passed the check has already finished its `put()` before
releasing the lock. So by the time `shutdownNow()` acquires the lock and sets the flag, no further
task can ever reach the queue — the flag flip alone is a complete fence, and the drain doesn't need
to be co-located with it. Note this argument is **non-local**: it depends on `execute()` keeping the
`put()` inside the lock. Narrow that critical section later and this silently breaks.

**Why drain before interrupt.** The worker only learns about an interrupt as an
`InterruptedException` out of `take()`, and `take()` only reaches `notEmpty.await()` when the queue
is *empty*. On a non-empty queue it just grabs an item and returns, never consulting the interrupt
flag. So interrupting first would let workers keep pulling tasks off while you drain, and the
returned list would stop being a clean "here's exactly what never began." Draining first empties the
queue, so no worker *can* pull anything else; only then do you interrupt.

`ThreadPoolExecutor` does the opposite order (interrupt, then drain) and is still correct, because
its `getTask()` consults the run state on every iteration — the state advance is its fence, so it
doesn't need the drain to be one.

**The returned list is the point.** `execute()` *accepted* those tasks; the caller got no exception
and believes the work is committed. `shutdownNow()` is where that promise is revoked, and the return
value is the receipt — resubmit to a replacement pool, persist for retry, or log what was dropped.
Same signature as the real `ExecutorService.shutdownNow()`; `shutdown()` returns `void` because
graceful shutdown by definition abandons nothing.

## `awaitTermination`: one shared budget, and the `join(0)` trap

```java
long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
for (Thread worker : workers) {
    long remainingNano = deadline - System.nanoTime();
    if (remainingNano / 1_000_000 <= 0) return isTerminated();
    worker.join(remainingNano / 1_000_000);
}
return isTerminated();
```

- **Absolute deadline, computed once.** The naive `for (w : workers) w.join(timeoutMillis)` gives
  each join a *fresh* timeout — 4 workers × 5 s can block for 20 s against a 5 s request. Converting
  the relative timeout to an absolute deadline up front turns N independent timeouts into one shared
  budget.
- **`nanoTime`, not `currentTimeMillis`.** Monotonic. Wall-clock time can jump backward or forward
  on an NTP correction mid-wait.
- **Joining sequentially doesn't sum.** The workers die concurrently; total wall time is bounded by
  the slowest one, which you have to wait for regardless. Iteration order is irrelevant.
- **The guard compares the same truncated value it passes to `join`.** This is the subtle one.
  `Thread.join(0)` means *wait forever*, and `Thread.join(negative)` throws
  `IllegalArgumentException`. Integer division means a sub-millisecond remainder (999,999 ns)
  truncates to `0` — so guarding on the **nano** value while passing the **milli** value reintroduces
  an unbounded block. An earlier revision had exactly that bug: `awaitTermination(500, MICROSECONDS)`
  hung indefinitely. One check on the truncated value closes both trapdoors.
- **The overflow is benign.** `System.nanoTime() + toNanos(Long.MAX_VALUE)` does overflow to a
  negative deadline, but `deadline - System.nanoTime()` underflows in the opposite direction and the
  two cancel. That is exactly why the deadline-**subtraction** form is preferred over
  `now < deadline` — it's overflow-tolerant for any interval under ~292 years. Verified:
  `awaitTermination(Long.MAX_VALUE, NANOSECONDS)` — the standard "wait indefinitely" idiom — waits
  correctly rather than returning immediately. Do not "fix" this with a clamp.
- **Returns `isTerminated()`, never a bare `true`.** `Thread.join(long)` returns `void` — it cannot
  tell you whether the thread died or the timeout expired. Both exit paths funnel through the same
  check, so the early return re-scans *all* workers, including ones never joined, and can't report
  success it didn't verify.

Joining threads only works because the worker set is fixed. `ThreadPoolExecutor` can't do this —
workers are created and reaped for core/max sizing and keep-alive — so it waits on a `termination`
`Condition` that `tryTerminate()` signals instead.

## `shutdownNow()`'s interrupt is best-effort, not a guarantee

If a running task never calls anything interruptible (no `sleep`, no blocking I/O, no
`Thread.interrupted()` check of its own), `worker.interrupt()` sets the flag but has **no visible
effect** until that task naturally finishes — same contract as the real `ExecutorService`. The test
suite's `shutdownNow` case is written around a task that explicitly cooperates (sleeps, catches the
interrupt, restores the flag) specifically to make this observable.

## Known limitations (deliberate, not oversights)

- **`execute()` and `shutdown()` block inside `put()` while holding `lock`.** If the queue is full
  and every worker is stuck in a long task, the submitter parks holding the pool lock and
  `shutdownNow()` can't acquire it — the one call meant to rescue a wedged pool is blocked by the
  wedge. `ThreadPoolExecutor` avoids this because its submit path doesn't hold `mainLock` across the
  enqueue. The structural fix is moving shutdown state into the queue so the wait releases the lock.
- **The worker's `isShutdownNow` check sits after `take()`.** A worker can dequeue a real task,
  *then* observe the flag, and return — that task is already off the queue, so `drainAll()` won't
  return it either: neither run nor reported. The window is a couple of instructions wide (the worker
  must be descheduled between `take()` returning and the flag read, spanning the flag write);
  120 trials × 400 tasks with `shutdownNow()` fired mid-submission produced **zero** losses. Moving
  the check above `take()` would close it for free, and matches `ThreadPoolExecutor`'s rule: consult
  run state, *then* poll — **once a task is dequeued, it always runs.**
- **`shutdownNow()` after `shutdown()`** returns the leftover poison pills inside the `neverStarted`
  list, since `drainAll()` doesn't filter them.

## Verified, not just argued

Ran `Test/MyThreadPoolTest.java` (plain `main()`-based, no JUnit, matching this repo's
`P01_MultiThreadedDFS` test style) **20 times in a loop** — 20/20 clean passes, no hangs. Covers:

- all submitted tasks complete before `shutdown()` returns/terminates, including under a
  deliberately tiny queue capacity (3) that forces `execute()` to actually block on backpressure
- `execute()` after `shutdown()` throws `RejectedExecutionException`
- `shutdown()` called twice doesn't hang or double-enqueue pills (idempotent)
- `shutdownNow()` on a single-worker pool: the actively-running task's thread observes the
  interrupt (confirmed via a counter incremented inside its `catch (InterruptedException)`), and
  the exact 3 queued-but-never-started tasks come back from `shutdownNow()`'s return value
- 8 concurrent submitter threads × 25 tasks each = 200, all run exactly once, none lost or
  double-counted
- `execute(null)` throws `NullPointerException`
- a task that throws `RuntimeException` doesn't kill its worker — 5 tasks submitted afterward on
  the same single-worker pool all still run

Separately, outside the suite: `awaitTermination(Long.MAX_VALUE, NANOSECONDS)` waits correctly
(overflow cancels), and a 120-trial harness asserting `accepted == started + neverStarted.size()`
across a concurrent `shutdownNow()` found no lost tasks.

## 30-second recall

> Bounded blocking queue = `Lock` + two `Condition`s (`notFull`/`notEmpty`), single `signal()` per
> op since it's one-in-one-out. Workers loop `take()` → run; graceful shutdown = one poison-pill
> sentinel per worker (**`poolSize` pills, not `queue.size()`**), enqueued behind whatever's already
> queued, so FIFO order guarantees every real task runs before any worker exits. The subtle bug class
> isn't in the queue, it's the **shutdown-vs-submit race** — `execute()`'s check+put and
> `shutdown()`'s flag+pill-batch each need to be one atomic unit under a shared `lock`, because the
> invariant is "a queued task sits ahead of every pill," not "the flag reads coherently."
> Flags are `volatile`, written under the lock and read lock-free: use the **lock** where a read
> authorizes a later action, **`volatile`** for standalone status reads — taking the lock in the
> worker loop instead deadlocks against a submitter parked in `put()` while holding it.
> `shutdownNow()` drains **before** interrupting (a non-empty `take()` never checks the interrupt
> flag) and can drain outside the lock only because `execute()` holds it across the `put()`.
> `awaitTermination` = one absolute `nanoTime` deadline shared across all joins, guard on the same
> truncated millis you pass to `join` (`join(0)` waits forever), and return `isTerminated()` because
> timed `join` returns `void`. Verified 20/20 clean runs including a 200-task, 8-concurrent-submitter
> stress case.
