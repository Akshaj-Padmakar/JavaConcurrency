# Thread Pool With Shutdown

> Interview form: _"Implement your own thread pool"_ — no `ExecutorService`,
> `ThreadPoolExecutor`, `java.util.concurrent.BlockingQueue`, or `Future`. Build the blocking
> queue and the pool yourself, and get shutdown semantics right, not just "run tasks on N threads."

## Problem

Implement a fixed-size thread pool from scratch:

```java
class MyThreadPool {
    MyThreadPool(int poolSize, int queueCapacity);
    void execute(Runnable task);           // submit work; blocks if the queue is full
    void shutdown();                        // graceful: finish everything queued, then stop
    List<Runnable> shutdownNow();           // abrupt: stop ASAP, return what never started
    boolean awaitTermination(long timeout, TimeUnit unit);
    boolean isShutdown();
    boolean isTerminated();
}
```

Backed by your own bounded blocking queue (`put`/`take`, blocks when full/empty) — not
`java.util.concurrent`'s.

## Why "with shutdown" is the actual point

"N worker threads pulling off a shared queue" is the easy 15 minutes. The part that separates a
real answer from a toy one is: **how does a worker thread know to stop, and when is it safe for it
to stop?** A worker blocked in `queue.take()` can't just "check a flag" — it's parked, not polling.
And a graceful shutdown has a harder constraint than it first looks like: every task queued
**before** shutdown was requested must still run; nothing after should be accepted.

## Requirements

- **Backpressure.** `execute()` should block (not silently drop or unboundedly grow) when the
  queue is full — that's the whole reason to hand-roll the queue instead of using an unbounded
  `LinkedList`.
- **Graceful shutdown** (`shutdown()`): stop accepting new work; every task already queued or
  running must complete; then the pool terminates. New `execute()` calls after this point must be
  rejected, not silently swallowed or queued.
- **Abrupt shutdown** (`shutdownNow()`): stop accepting new work, make a best-effort attempt to
  stop what's currently running (does *not* guarantee an uncooperative task actually stops early —
  same contract as the real `ExecutorService`), and return the tasks that were queued but never
  got to start.
- **Task isolation.** One task throwing an exception must not kill its worker thread or silently
  stop the pool from processing anything else.
- **`awaitTermination`** with a timeout, and `isShutdown()`/`isTerminated()` status queries.

## Points to Ponder

- **How does a worker blocked in `take()` learn it's time to exit** — without busy-polling a flag,
  and without a fixed sleep/timeout? (Hint: what if the "stop" signal were itself just another item
  that could be *put into* the queue?)
- **The shutdown/submit race.** A caller can call `execute()` at literally the same instant another
  thread calls `shutdown()`. What has to be true for the outcome to be well-defined — either the
  task is guaranteed to run, or it's guaranteed to be rejected, never a state where it's silently
  neither?
- **Why does `shutdownNow()` returning "tasks that never started" matter**, versus just returning
  nothing? What would a caller actually do with that list?
- **What happens to a task that's already running when `shutdownNow()` is called** and it never
  checks `Thread.interrupted()` or calls anything interruptible? Is that a bug, or expected?
- **Fairness.** With multiple threads calling `execute()` concurrently against a small bounded
  queue, is there any guarantee about submission order being preserved? Does it need to be?
- **Extending to `submit(Callable<T>)` returning a `Future<T>`.** What would you need to add —
  and where would exceptions thrown by the task surface to the caller?
- **Where does the real `java.util.concurrent.ThreadPoolExecutor` differ** from the "obvious"
  design here? (It avoids one global lock across submit/shutdown by packing running-state and
  worker-count into a single `AtomicInteger` it manipulates with CAS — worth knowing that trade-off
  exists even if you don't implement it.)
