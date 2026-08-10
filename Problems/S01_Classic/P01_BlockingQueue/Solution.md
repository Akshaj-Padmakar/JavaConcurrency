# Bounded Blocking Queue — Solution

## 1. The idea

One lock, two condition variables. Producers wait on *not-full*, consumers wait on *not-empty*, and
every method that changes the contents signals the opposite side before releasing the lock.

## 2. State model

| State | Predicate | Who waits here | Who wakes them |
|---|---|---|---|
| empty | `size == 0` | `take`, `poll(t)` on `emptyCondition` | `addItem()` |
| full | `size == capacity` | `put`, `offer(t)` on `fullCondition` | `pollElement()` |
| neither | `0 < size < capacity` | nobody | — |

Two conditions, not one, because the two waiter groups have **different predicates**. That single
fact drives the whole design.

## 3. Mechanism, and the traps

```java
lock.lock();                      // lockInterruptibly() on blocking paths
try {
    while (predicateFails()) cond.await();
    mutate();                     // helper owns BOTH the mutation and the signal
    otherCond.signal();
} finally { lock.unlock(); }
```

- **`while`, never `if`** — two independent reasons, and you want both out loud: spurious wakeups,
  *and* barging. A non-fair `ReentrantLock` lets a thread that never waited jump in and take the
  item between the signal and the woken thread's re-acquisition. `if` then falls through to a poll
  on an empty queue. (Barging is the one people forget; it's also the one that actually fires — see
  §9.)
- **Route every mutation through one helper.** `addItem()` mutates *and* signals. The moment a path
  calls `queue.add()` directly, the signal is gone. This is the bug that actually happened.
- **Signal inside the lock, before release.** Ordering *within* the critical section is harmless —
  the woken thread can't run until you unlock — but signalling before mutating still reads as a bug
  and becomes one if anything can return or throw between the two lines.
- **Timed loops recompute the *remaining* time, never the original timeout.** Deadline once, up
  front; `nanos = deadline - System.nanoTime()` each iteration. That subtraction is overflow-safe
  even when `toNanos` saturates at `Long.MAX_VALUE` — the wraparound in the subtraction cancels the
  overflow in the addition.
- **`signal()`, not `signalAll()`** — correct *here* because every waiter on a given condition has
  the identical predicate, so one wakeup suffices. `signalAll()` would be a thundering herd. This
  reasoning inverts the moment you drop to `synchronized` (§8).

## 4. What to ask the interviewer

1. "`java.util.concurrent` locks, or do you want the primitives built from `synchronized`/`wait`/`notify`?"
2. "Bounded or unbounded? If bounded, what should a producer do when it's full — block, drop, or throw?"
3. "Do you want the timed and non-blocking variants, or just `put`/`take`?"
4. "Strict FIFO across all producers, or is per-producer ordering enough?"
5. "Does this need a shutdown path, or does it live for the process lifetime?"

## 5. Answers to Problem.md §7

1. **Block:** `put`, `take`, both timed variants. **Never block:** `add`, `offer`, `poll`, `peek`,
   `size`, `remainingCapacity`. Two waiting reasons ⇒ two wait-sets.
2. **One condition would hang, not corrupt.** `signal()` could wake a producer when the event was
   "space freed" — it re-checks, still full, sleeps again, and the consumer that needed waking is
   never reached. Lost wakeup with a correct-looking queue. `signalAll()` on a single condition is
   *correct but wasteful*: every blocked thread wakes to re-check a predicate that's false for most.
3. **Signal the opposite condition before releasing the lock.** Mutating methods: `add`, `offer`,
   `put`, `offer(t)`, `poll`, `take`, `poll(t)` — **seven**, and `add` is the one that gets forgotten
   because it doesn't *look* like a blocking method.
4. **`signal()`.** Decided by: all waiters on one condition share one predicate. Wrong direction one
   way = missed wakeups and hangs; the other way = thundering herd, a perf cost only.
5. Spurious wakeups, and barging (§3).
6. **Re-test the predicate and loop.** Do *not* recompute the deadline — only the remaining time.
7. **Not safe**, and the bug is in the **caller**. `size()` is a snapshot that's stale before it
   returns. There's no API fix; the whole point of `offer()` is to make the check-and-act atomic.
8. **Reject it.** `capacity <= 0` makes `isFull()` permanently false for negatives — the bound
   silently doesn't exist — and makes `put()` block forever for 0.
9. **The signal is consumed and no one else is woken.** AQS transfers the node, then the interrupt
   wins and `await` throws. The JDK has the same hole; it's accepted because interrupt means
   "abandon", and the next `put` re-signals. Worth naming, not worth fixing.
10. **Scheduling only.** Fairness changes *who* proceeds, never whether the queue is correct.
    Default **off** — fair locks cost throughput badly, and starvation is rare with symmetric waiters.

## 6. What the interviewer is checking

| Signal | What it proves |
|---|---|
| Two conditions, unprompted | You understand a condvar is a *queue per predicate*, not a lock |
| `while` + both reasons | You've actually debugged a lost wakeup |
| One coarse lock first, refine after | You sequence correctness before performance |
| Naming what the timeout bounds | You read contracts instead of guessing |
| `signal` vs `signalAll` justified | You reason about waiter sets, not idioms |
| Asking about the library rule | Their doc scores clarifying explicitly |

## 7. What fails you

- Reaching for `ArrayBlockingQueue` — that *is* the question.
- `if` around `await`.
- A busy-wait/spin loop, even one that produces correct output.
- Forgetting to signal on the non-blocking insert path (`add`/`offer`).
- Recomputing the full timeout inside the wait loop.
- Returning `false` from a timed `offer` when the queue had room.
- `size()` used as a guard, in your own code or your example usage.
- Silence. Narrate; they grade it.

## 8. Extensions

**"Do it with `synchronized`/`wait`/`notify`."** → One monitor, `wait()` in a `while`, and you
**must** use `notifyAll()`. *Trap:* a single monitor merges both waiter groups, so `notify()` can
wake a producer when a consumer was needed — the §5.2 hang. This is the most likely follow-up given
the library rule; it's also the one where `signal()` reasoning inverts.

**"Make it scale — two locks?"** → `LinkedBlockingQueue` uses separate `putLock`/`takeLock` with an
`AtomicInteger count`, so a producer and consumer never contend. *Trap:* `size()` and `remainingCapacity()`
now read shared state under neither lock, and the cascading-signal logic (a `put` that fills the queue
must sometimes signal `notEmpty`) is where it goes wrong. Only viable because head and tail are
disjoint — impossible on an array ring.

**"Add `drainTo(collection, max)`."** → Take everything under one lock acquisition, signal `notFull`
once. *Trap:* the caller's collection can throw mid-drain, leaving elements consumed but undelivered.

**"How do you shut it down?"** → A `closed` flag plus `signalAll()`; subsequent `put` throws,
`take` drains then throws. *Trap:* poison pills need one per consumer and don't work with unknown
consumer counts.

**"Bound by bytes, not count."** → Predicate becomes `bytes + item.size > limit`. *Trap:* waiters
now have **different** predicates (a small item may fit where a large one won't), so `signal()`
becomes wrong and you need `signalAll()`.

**"Priority instead of FIFO."** → Swap in a heap. *Trap:* unbounded starvation of low-priority items,
and `peek`-then-`poll` no longer returns the same element.

## 9. Bug log

| Bug | Symptom | Lesson |
|---|---|---|
| `add()` called `queue.add()` instead of `addItem()` | Consumer parked in `take()` forever with the item sitting in the queue; stack trace showed `ConditionObject.await`. Self-heals on the next `put`, so it reads as an intermittent stall | Once a helper owns the signalling, **no path may touch the backing collection directly** |
| Null threw `IllegalArgumentException` | Suite stayed green — it asserted no exception types | `Collection` contract: **NPE** for null, **ISE** for a capacity state, **IAE** for a bad *property* of an element |
| Both deadline checks kept after adding the JDK-idiom one | Nothing — but mutation testing showed deleting *either* leaves the suite green | Two guards for one condition means one is dead. The early `if (!awaitSuccess) return` also preempts the Case-B re-check |
| *(my probe)* Reported FIFO-BREAK with 6 consumers | False positive | With multiple consumers, the order you *record* items isn't the order they were *dequeued*. Ordering is only externally checkable with one consumer |
| *(my probe)* Claimed `nanoTime() + toNanos(MAX)` overflows | It doesn't | The subtraction wraps back. Second time an "overflow bug" here was wrong — test it before claiming it |
| *(my test)* Clock-restart test passed on the mutant | Vacuous test | Mutation-test the tests. The waiter succeeded 12/12 in 0–13ms and never looped |

## 10. Known limitations — deliberate trades

- **`LinkedList`, not a ring buffer.** One node allocation per element vs `ArrayBlockingQueue`'s
  preallocated array. Chosen for writing speed under time pressure; say so before they ask.
- **One lock for both ends.** Producers and consumers contend even when the queue is half full.
  Correct-first; §8 has the two-lock refinement.
- **Case B**: a timed call still returns `false` if a slot frees at the exact instant of expiry.
- **Timed variants use `lock.lock()`**, while `put`/`take` use `lockInterruptibly()` — inconsistent.
- **No shutdown path.** The queue lives forever.

## 11. Verified

17 tests, 8/8 clean runs, ~3.75s, no flakes. Every claim below was mutation-tested — a copy of the
implementation was broken and the suite confirmed to fail.

**Covered:** FIFO and no loss/duplication (4 producers × 4000, capacity 4, single consumer so
dequeue order is observable) · capacity never exceeded (watchdog thread) · `add`/`offer` wake a
blocked `take` · one freed slot releases exactly one producer · `take` never returns null under
6-consumer barging · `put`/`take` interruptible · null → NPE on all four insert paths · capacity
validation · expired deadline still inserts when not full · timed budget bounds · `peek` idempotent.

**Not covered, and why:**
- **Clock restart under repeated wakeups** — not reachable externally. Every wakeup means a slot
  genuinely opened, and a correct timed call is then supposed to succeed. Drainer and barger designs
  both produced 12/12 immediate successes.
- **Case B** — reading-level only; the window is microseconds wide.
- **Fairness ordering** — `fair=true` is constructed but its FIFO guarantee is never asserted.
- Green here bounds what was tested, not what is correct.

## 12. 30-second recall

> **One lock, two conditions** — waiters split by predicate: `notFull` for producers, `notEmpty` for
> consumers. `while (predicate) await()` — **spurious wakeups AND barging**. Every mutation goes
> through one helper that mutates *and* signals the opposite condition before unlock; the forgotten
> path is **`add()`**, because it doesn't look like it blocks. `signal()` not `signalAll()` because
> all waiters on one condition share one predicate — that inverts on a single `synchronized` monitor,
> where you **must** use `notifyAll()`. Timed calls: deadline computed **once**, recompute only the
> *remaining* nanos; the timeout bounds **waiting for space, not the call** — so if the deadline has
> passed but the queue isn't full, **insert and return true**. Never fail an operation you can
> complete. Contract: **NPE** null, **ISE** full, **IAE** bad capacity. `size()` is a stale snapshot —
> never a guard. Scale-up: two locks (`putLock`/`takeLock` + atomic count); memory: ring buffer.
