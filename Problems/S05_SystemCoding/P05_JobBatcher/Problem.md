# Job Batcher Service

> Interview form: _"Jobs arrive with a type and an id. Run them on a worker pool, but never run two
> jobs for the same id at the same time."_
> The pool is easy. The constraint is what the problem is about — and the obvious solution to it is
> the wrong one.

## What you're building

The scheduler behind a backup service. Work arrives continuously:

```
snapshot  vm-42        replicate  vm-42        expire  fileset-7
snapshot  vm-99        index      vm-42        snapshot  vm-42
```

Each job has a **type** (what to do) and an **id** (what to do it to). You have a fixed pool of
workers — say 8 — and thousands of ids.

One hard rule:

> **Two jobs for the same id must never run at the same time.**

Snapshotting `vm-42` twice concurrently corrupts the snapshot. Expiring `vm-42` while replicating it
reads storage that's being freed. Different ids are completely independent and should run in
parallel — that's the whole point of having 8 workers.

## Why the obvious answer is wrong

**"Give each id a lock."** A worker picks up a job for `vm-42`, finds the lock held, and **blocks
while holding a pool thread**. Eight jobs for one hot id and your entire pool is asleep with
thousands of other ids waiting. You've also created a lock object per id, forever.

**"Give each id a thread."** Thousands of ids, thousands of threads.

Per-id exclusion has to be a **scheduling** property, not a locking one: a worker should never be
handed a job it can't run.

## A tiny example

3 workers. Jobs submitted in this order:

```
J1  snapshot  vm-42
J2  snapshot  vm-99
J3  index     vm-42        <- same id as J1
J4  expire    fileset-7
J5  index     vm-99        <- same id as J2
```

```
t0   worker A takes J1 (vm-42)     vm-42 now busy
     worker B takes J2 (vm-99)     vm-99 now busy
     worker C takes J4 (fileset-7)
     J3 and J5 are NOT handed out -- their ids are busy
     worker C finishes J4, and idles even though J3 and J5 are pending

t1   worker A finishes J1          vm-42 free -> J3 becomes runnable
     some worker takes J3

t2   worker B finishes J2          vm-99 free -> J5 becomes runnable
```

Three workers, five jobs, and at `t0` a worker sits idle **on purpose**. That's correct: the pending
work is all blocked behind busy ids.

## What you implement

```java
class JobBatcher {
    JobBatcher(int workerCount, Consumer<Job> handler);

    /** Queue a job. Returns when queued, not when executed. */
    void submit(Job job);

    /** Stop accepting work; run everything already queued; then let workers exit. */
    void shutdown();

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
}

class Job {
    final String type;
    final String id;      // the exclusion key
    // ... payload
}
```

## Constraint

`Thread`, `synchronized`, `wait`/`notify` (or `Lock` + `Condition`). Build the worker pool yourself
— you have `MyThreadPool` from [P06](../../S00_General/P06_ThreadPoolWithShutdown/Problem.md) if you
want to reuse it. No `ExecutorService`, no `ConcurrentHashMap`.

## Requirements

- **Never two jobs for one id concurrently.** The whole point.
- **Jobs for the same id run in submission order.** J1 before J3, always.
- **Different ids run in parallel** — up to `workerCount` at a time.
- **Workers never block on a busy id.** A worker either gets runnable work or waits for *any* work;
  it never holds a thread waiting for one specific id.
- **A throwing job doesn't wedge its id.** The next job for that id must still run.
- **Clean shutdown.** Everything submitted before `shutdown()` runs; workers exit; nothing hangs.

## Edge cases

Two jobs for the same id submitted back to back · a job submitted *while* its id is running · a
thousand jobs for one id and one job for another (the second must not starve) · `submit()` after
`shutdown()` · a job that throws · a job that blocks for a long time · `workerCount` of 1 · an id
that never appears again after its last job.

## Three questions to answer before you code

**What do workers take from?** Not a queue of jobs — a worker pulling a job whose id is busy has to
put it back, and then it spins. Think about what collection a worker can pull from where
**everything in it is runnable by definition.**

**What has to happen atomically when a job finishes?** The worker must mark the id free *and* decide
whether that id has more work queued. Split those two steps and a second worker can grab the id in
between — two jobs, same id, concurrently. That's the exact thing the design exists to prevent, and
it hides on the completion path where it doesn't look like an acquisition.

**How does a worker wait?** There may be pending jobs but nothing runnable (every id busy). The
worker must block, and something must wake it — what event makes previously-unrunnable work
runnable, and who signals it?

## Part 2: batching

Once per-id serialization works, the interviewer adds:

> _"Metadata updates are one round trip each and that's too slow. Batch jobs of the same **type** and
> hand the handler a whole list at once — flush when you have 50, or when the oldest has been waiting
> 100 ms, whichever comes first."_

```java
JobBatcher(int workerCount, int maxBatchSize, long maxDelayMillis, Consumer<List<Job>> handler);
```

The new mechanism is the **timer**. A batch may sit under-full indefinitely, so something has to
flush it on a deadline — and when a batch flushes early because it filled up, the deadline for the
*next* batch has to be re-armed. Get that wrong and jobs sit for one extra flush interval, which
nobody notices until latency graphs look strange.

Questions worth having answers for: does batching interact with the per-id rule (can one batch
contain two jobs for the same id)? Does a timed flush need its own thread, or can a worker do it
with a timed wait?

## The jargon

| Plain version | The term |
| ------------- | -------- |
| Never two jobs for one id at once | **per-key mutual exclusion** / key-level serialization |
| Workers pull keys that are known runnable | **dynamic partitioning** — as opposed to static (thread-per-key) |
| One busy id must not stall the pool | avoiding **head-of-line blocking** |
| One hot id must not starve the rest | **fairness** across keys |
| Group N jobs into one handler call | **batching** / micro-batching; **group commit** in storage systems |
| Flush on size or on a deadline | **triggered flush** — size-triggered and time-triggered |
