# Job Batcher Service — Revision Sheet

> See [Problem.md](Problem.md). Implementation: `JobBatcher.java`, `Test/JobBatcherTest.java`.

## One-line idea

Workers pull from **`ready`, a queue of ids — not a queue of jobs.** Everything in `ready` is
runnable by construction, so a worker never picks up work it can't run and never blocks waiting for
one particular id. Per-id exclusion becomes a **scheduling** property instead of a locking one.

## State model

| Field | Meaning |
| ----- | ------- |
| `pending` | `id -> Deque<Job>`, FIFO. Jobs waiting, grouped by exclusion key |
| `running` | ids with a job in the handler **right now** |
| `ready` | ids that have work **and** are not running — the only thing workers pull from |
| `pendingJobs` | submitted but not yet completed. Drives termination |
| `shutdown` / `shutdownNow` | graceful and abrupt flags |

An id is always in exactly one state: **idle** (absent everywhere), **ready** (in `ready`), or
**running** (in `running`, possibly with more jobs queued in `pending`).

## Why "a lock per id" is the wrong answer

It's the answer most people give, and it fails twice:

- A worker that takes a job for a busy id **blocks while holding a pool thread**. Eight jobs for one
  hot id and the whole pool is asleep with thousands of other ids waiting.
- You create a lock object per id, forever.

"A thread per id" fails for the obvious reason. The fix is that a worker should **never be handed a
job it cannot run**.

## Static vs dynamic partitioning — know the trade

The other real design is hash-partitioning to per-worker queues:

```java
int worker = Math.floorMod(job.id.hashCode(), workerCount);
queues[worker].put(job);
```

Same id always lands on the same worker, which runs its queue serially — so exclusion and ordering
come **free, with no shared state and no locking at all**. Genuinely elegant, and it's what Kafka
consumer groups and most actor dispatchers do.

Its cost is that partitioning is **static**:

```
worker 3: [vm-42 snapshot .....]  running, 2 hours
          [vm-50 index]           waiting 2 hours
          [vm-58 expire]          waiting 2 hours
workers 0,1,2,4..7: idle
```

`vm-50` has nothing to do with `vm-42`; a hash function put them in the same bucket.

| | static (queue per worker) | dynamic (shared `ready`) |
| --- | --- | --- |
| Per-id ordering | free, lock-free hot path | needs shared state + a lock |
| Load balance | poor when ids are skewed | any free worker takes any runnable id |
| Head-of-line blocking | **yes**, across unrelated ids | no |
| Complexity | trivial | moderate |

Static wins when jobs are **short and uniform**. Dynamic wins when durations vary by orders of
magnitude — a 30-second index and a two-hour snapshot must not share a queue. Say which you picked
and why; that you *considered* the simpler one matters.

## The three places it can go wrong

### 1. `submit` — deciding whether the id becomes runnable

```java
boolean becameRunnable = queue.isEmpty() && !running.contains(job.getId());
queue.add(job);
pendingJobs++;
if (becameRunnable) { ready.add(job.getId()); workAvailable.signal(); }
```

An id is newly runnable **only** if it had no queued work *and* isn't in flight. Any other state
means it's already in `ready`, or will be re-queued when its current job finishes.

Get this wrong in the permissive direction and the id lands in `ready` **twice** — two workers take
it, and two jobs for the same id run concurrently. The exact thing the design exists to prevent.

### 2. `finish` — release and re-queue must be ONE critical section

```java
running.remove(id);
pendingJobs--;
Deque<Job> queue = pending.get(id);
if (queue == null || queue.isEmpty()) pending.remove(id);
else { ready.add(id); workAvailable.signal(); }
```

Split `running.remove(id)` from the re-queue decision and another worker claims the id in the gap.
Same check-then-act shape as everywhere else — but on the **completion** path, where it doesn't look
like an acquisition, which is why it's the one people miss.

`pending.remove(id)` when the queue empties matters too: without it, every id ever seen leaves a
permanent empty `Deque` behind.

### 3. Termination — count outstanding work, don't check the queue

```java
while (!shutdownNow && ready.isEmpty() && !(shutdown && pendingJobs == 0)) await();
if (ready.isEmpty() || shutdownNow) return;
```

**Poison pills do not work here.** Unlike a plain thread pool, work is *regenerated* during draining
— a finishing job re-queues its id, which lands *behind* any pill already in the queue. A worker
eats a pill and exits while work remains, and eventually the last worker leaves with jobs stranded.

Counting outstanding jobs is the fix. And note "`ready` is empty" is never a stopping condition on
its own: all ids may be busy with more work queued behind them.

## `signal` vs `signalAll` — the distinction this problem teaches

Four call sites, and only two are broadcasts:

| Where | Event | Unblocks | Call |
| --- | --- | --- | --- |
| `submit` | one id became runnable | **one** worker | `signal()` |
| `finish` (re-queue) | one id became runnable again | **one** worker | `signal()` |
| `shutdown` | we are terminating | **every** worker | `signalAll()` |
| `finish` (drained) | terminating and drained | **every** worker | `signalAll()` |

> **`signal()` when the state change lets one waiter proceed. `signalAll()` when it lets all of them
> proceed.**

A unit of work is a hand-off to a single thread. Termination is a broadcast — every worker's
predicate flips at the same instant.

This is a *different* reason from the `RangeLock` and assembler cases, where `signalAll` was required
because waiters had **individual** predicates. Here every worker waits on the same predicate; what
decides it is **how many waiters the event unblocks**.

Getting it wrong was the real bug: with `signal()` on both termination paths, exactly one of eight
workers woke and exited. The other seven parked forever, `awaitTermination` returned `false`, and the
threads leaked — while all 60 test jobs had run correctly. **The draining logic was right; only the
exit path was broken**, which is exactly the kind of bug a functional test misses.

## The two shutdown modes

**`shutdown()` — graceful.** Stop accepting; everything already submitted runs; terminate when
`pendingJobs == 0`.

**`shutdownNow()` — abrupt.** Drain `pending` into a returned list, clear `ready`, interrupt the
workers, exit on the flag.

```java
pendingJobs = running.size();   // after draining, only in-flight jobs are still outstanding
```

That line keeps `finish()`'s decrements balanced so the counter still lands on zero as the
interrupted jobs unwind. Setting it to `0` instead would drive it negative.

**The predicate change is the part that's easy to miss.** After draining, `ready` is empty but
`pendingJobs > 0` while anything is in flight — so the graceful predicate says "keep waiting," and no
work will ever become ready again because `pending` is empty. Idle workers park forever. The
graceful path terminates on *drained*; the abrupt path has to terminate on *told to stop*.

The interrupt is **best-effort**, same contract as `ExecutorService.shutdownNow()` — a job that never
calls anything interruptible runs to completion regardless.

### The accounting guarantee

```
started + returned == submitted
```

Every job either entered the handler or came back in the list. Never both, never neither. That's why
`shutdownNow()` returns the jobs instead of discarding them: the caller can requeue them elsewhere,
persist them, or at minimum log what was dropped.

## The sharp edge: chained submissions are dropped during shutdown

The natural way to express "replicate after snapshot" is to submit from inside the handler:

```java
handler = job -> {
    snapshotService.take(job.getId());
    batcher.submit(new Job("replicate", job.getId()));   // <- throws once shutdown is set
};
```

Measured with 20 snapshots and `shutdown()` landing mid-flight:

```
snapshots run      : 20
replications run   : 4
replications LOST  : 16   <- IllegalStateException swallowed by the worker's catch(Throwable)
```

So the guarantee is narrower than it looks:

> **Everything submitted before `shutdown()` runs. Work *generated by* those jobs does not.**

For a backup service that's data loss wearing a success message. Three defensible answers — allow
self-submission from worker threads (risking a chain that never lets shutdown finish), surface the
rejection to a caller-supplied error handler instead of swallowing it, or document that chaining is
unsupported. **The only clearly wrong option is the silent one.**

## Known limitations

- **Chained submissions are dropped silently** during shutdown — see above.
- **`abandoned` has arbitrary cross-id order.** `pending.values()` iterates a `HashMap`, so jobs come
  back grouped by id, FIFO within an id, but ids in hash order. Fine for logging or requeueing; not
  if a caller assumes submission order.
- **No status accessors.** No `isShutdown()`, `isTerminated()`, or `outstanding()`, so
  `awaitTermination` returning `false` can't distinguish "still draining" from "wedged."
- **No per-job completion signal.** `submit()` returns `void` — no `Future`, no callback. Chaining
  from inside the handler is the only composition mechanism, and it has the flaw above.
- **No fairness across ids.** `ready` is FIFO over ids, which is reasonable, but a pathological
  submitter could keep one id perpetually at the head.
- **Part 2 (size/time batching) is not implemented** — the `type` field exists but nothing groups on
  it yet.

## Verified

`Test/JobBatcherTest.java` — plain `main()`-based, no JUnit. 14 cases, 3/3 clean runs. Every
potentially-blocking test uses a bounded wait, so a missing signal **fails with a message** rather
than freezing the suite — which is how the `signal`/`signalAll` bug surfaced.

The two that carry the weight:

- **40 ids × 50 jobs on 8 workers**, with exclusion and ordering checked **live inside the handler**:
  a shared `executing` set that flags a double entry the instant it happens, plus a per-id sequence
  check. Inferring this after the fact would miss it.
- **`shutdownNow()` accounting** — 200 jobs, two in flight sleeping 5 seconds each. Asserts
  `started + returned == 200` and that termination takes under 2 seconds, proving the interrupt was
  delivered rather than the jobs waited out. It completes in ~0 ms.

Plus: idle workers all exiting on `shutdown()` (the regression), six ids genuinely concurrent, a
throwing job not wedging its id, a hot id with 500 queued jobs not starving an unrelated one (the
cold job ran after fewer than 100), graceful drain of 300 jobs, `workerCount = 1`, submit-after-
shutdown rejected, `shutdownNow()` on an idle batcher, idempotence, graceful-then-abrupt escalation,
and invalid constructor arguments.

## 30-second recall

> **`ready` holds ids, not jobs** — everything in it is runnable by construction, so a worker never
> blocks on a specific id. `becameRunnable = queue.isEmpty() && !running.contains(id)`; enqueue an id
> twice and two workers run it concurrently. **`finish()` must release the id and decide on re-queue
> in ONE critical section** — check-then-act on the completion path, where it doesn't look like an
> acquisition. **Terminate on `pendingJobs == 0`, never on "queue empty"** — poison pills fail here
> because work is regenerated while draining. **`signal()` for a hand-off, `signalAll()` for a
> broadcast**: `signal` on new work, `signalAll` on both termination paths — get that wrong and one
> worker exits while the rest leak, with every job still running correctly. `shutdownNow()` sets
> `pendingJobs = running.size()`, needs its own term in the wait predicate, and guarantees
> **started + returned == submitted**. Versus hash-partitioned per-worker queues: those are lock-free
> and simpler but head-of-line block unrelated ids — the right trade only when job durations are
> uniform.
