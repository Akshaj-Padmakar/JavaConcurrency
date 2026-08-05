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

| Field                  | Meaning                                                                 |
| ------------------------ | -------------------------------------------------------------------------- |
| `taskQueue`             | `MyBlockingQueue<Runnable>` — bounded, gives `execute()` real backpressure |
| `workers`               | fixed list of `Thread`s, started in the constructor, never resized       |
| `isShutdown` / `isShutdownNow` | `volatile` status flags                                            |
| `stateLock`             | plain intrinsic lock guarding the shutdown/submit race — see below       |

## The race that actually matters (and why `stateLock` isn't optional)

`execute()` does "check `isShutdown`, then `put()`" — two steps. `shutdown()` does "set
`isShutdown`, then enqueue `poolSize` poison pills" — also multiple steps. Interleaved naively,
a task could land in the queue **after** some (not all) poison pills: whichever worker dequeues
that task's poison pill exits immediately without draining what comes after it, so if all workers
happen to reach their pills before any of them reaches that straggling real task, it's silently
stranded forever — `shutdown()` returns having "succeeded," the caller has no idea a task never ran.

Fix: `execute()`'s check-and-put and `shutdown()`'s flag-set-and-pill-enqueue are each done
**entirely** inside `synchronized (stateLock)`. That guarantees every `execute()` call is either
fully ordered before the whole shutdown-initiation (task is queued strictly before all pills →
guaranteed to run) or fully after it (sees `isShutdown == true` → rejected). No interleaving is
possible. `shutdownNow()` uses the same lock around its state flip + queue drain, for the same
reason — the returned "never started" list has to be a consistent snapshot, not something a
concurrent `execute()` call could still be adding to.

**Cost of this fix:** it fully serializes concurrent `execute()` calls against each other (only one
caller can be inside the `synchronized` block at a time, even if the queue has room for both) — a
throughput hit under many concurrent submitters, not a correctness one. Real
`java.util.concurrent.ThreadPoolExecutor` avoids exactly this by packing running-state and
worker-count into one `AtomicInteger` (`ctl`) manipulated with CAS instead of a single coarse lock
— worth knowing that trade-off exists even though this implementation takes the simpler, safer
route. Verified this doesn't actually break anything, at least: `testConcurrentSubmittersDontLoseOrCorruptTasks`
runs 8 threads submitting 25 tasks each (200 total) concurrently against the same pool.

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

## `shutdownNow()`'s interrupt is best-effort, not a guarantee

If a running task never calls anything interruptible (no `sleep`, no blocking I/O, no
`Thread.interrupted()` check of its own), `worker.interrupt()` sets the flag but has **no visible
effect** until that task naturally finishes — same contract as the real `ExecutorService`. The test
suite's `shutdownNow` case is written around a task that explicitly cooperates (sleeps, catches the
interrupt) specifically to make this observable; an uncooperative task would still eventually let
its worker notice the pending interrupt the next time it calls `take()` (interrupt status is
checked immediately on entry to `Condition.await()`, not only for interrupts that arrive *while*
already parked), so the worker does still exit — just not before finishing whatever it was
already doing.

## 30-second recall

> Bounded blocking queue = `Lock` + two `Condition`s (`notFull`/`notEmpty`), single `signal()` per
> op since it's one-in-one-out. Workers loop `take()` → run; graceful shutdown = one poison-pill
> sentinel per worker, enqueued behind whatever's already queued, so FIFO order guarantees every
> real task runs before any worker exits. The one subtle bug class here isn't in the queue, it's
> the **shutdown-vs-submit race** — `execute()`'s check+put and `shutdown()`'s flag+pill-batch each
> need to be one atomic unit under a shared `stateLock`, or a task can land interleaved with poison
> pills and get stranded. `shutdownNow()` drains the queue + interrupts workers, both under the
> same lock for a consistent "never started" snapshot; the interrupt itself is best-effort, matching
> `ExecutorService`'s real contract. Verified 20/20 clean runs including a 200-task, 8-concurrent-
> submitter stress case.
