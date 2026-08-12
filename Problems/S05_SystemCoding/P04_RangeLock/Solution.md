# Range Lock Manager — Revision Sheet

> See [Problem.md](Problem.md). Implementation: `RangeLock.java`, `Test/RangeLockTest.java`.

## One-line idea

Two collections under one lock: the ranges currently **granted**, and the ranges currently
**waiting**. A request is admissible when nothing granted conflicts with it — and, if it's a reader,
when no *waiting* writer overlaps it either. That second clause is the entire starvation fix.

Ranges are **closed**: `[start, end]` includes both endpoints, like an HTTP `Range: bytes=0-99`
header. Callers holding an offset and a length pass `offset, offset + length - 1`.

## State model

| Field       | Meaning                                                             |
| ----------- | ------------------------------------------------------------------- |
| `granted`   | ranges currently held; size bounded by **concurrency**, not file size |
| `waiting`   | ranges parked in `acquire`; exists so a blocked writer can fence readers |
| `lock` + `acquireCondition` | one lock, one condition, always `signalAll`         |
| `Entry`     | one grant. **Identity, not value** — see below                      |

## The conflict rule

```java
conflict(a, b) = overlaps(a, b) && (a.exclusive || b.exclusive)
```

Readers coexist. Anything touching a writer serializes. The whole problem is `overlaps`, and it is
where the off-by-one lives:

```
[0, 99] and [100, 199]   ->  adjacent, share NO byte      -> concurrent
[0, 99] and [ 99, 150]   ->  share exactly byte 99        -> serialize
```

For closed ranges: `a.start <= b.end && b.start <= a.end`.

**The direction of the mistake matters.** Writing `<` where you need `<=` lets a one-byte overlap
through — a genuine data race that surfaces as rare corruption, not as a hang, and a stress test has
to be unlucky to catch it. The half-open version fails the other way (adjacent ranges falsely
conflict), which only costs throughput. Closed intervals have the more dangerous failure mode; know
which convention you're in and test **both sides of the boundary**, which is why the suite has
`testAdjacentRangesDoNotBlock` and `testRangesSharingOneByteConflict` sitting next to each other.

With closed ranges `start == end` is a legitimate **one-byte** range. Empty ranges aren't
expressible at all, which removes an edge case rather than adding one — half-open needs an explicit
`if (a.start >= a.end) return false;` guard, because the two-comparison formula reports empty ranges
as overlapping everything.

## Why a handle, not `release(start, end)`

A public range-based release breaks four ways, and the last is unfixable:

1. You can release a range you never acquired.
2. A typo (`release(0, 100)` vs `release(0, 1000)`) either no-ops or frees someone else's range, and
   neither throws.
3. Nothing enforces release-exactly-once.
4. **It's ambiguous.** Two threads hold `[0, 100]` shared. `release(0, 100)` — *which* grant comes
   out? They're value-identical, so you can't tell them apart, so you can't maintain the table.

A handle names one specific grant: nothing to guess, nothing to forge. And it gives
try-with-resources, so the range is released on exception paths you didn't think about — the same
discipline as `lock()` + `finally { unlock(); }`, except the compiler enforces it.

The handle carries a `closed` flag so a second `close()` is a no-op. A bare lambda
(`() -> release(entry)`) would compile — `Handle` has one abstract method — but closing twice would
remove twice.

## Starvation is a liveness bug, not a throughput trade

Readers overlapping *each other* are all admissible, so with only a `granted` set, a stream of
readers means the writer's conflict test **never** comes up false. In the test, six readers cycling
over `[0, 999]` left a writer on `[400, 499]` stuck for the full 3-second timeout. That's a hung
thread, and no amount of waiting fixes it.

The fix is one extra collection, published **before** going to sleep:

```java
waiting.add(entry);                     // publish BEFORE awaiting
try {
    while (!isAllowed(entry)) acquireCondition.await();
} catch (InterruptedException ex) {
    waiting.remove(entry);
    acquireCondition.signalAll();       // we may have been fencing readers
    throw ex;
}
waiting.remove(entry);
granted.add(entry);
```

and the extra clause in `isAllowed`:

```java
if (!entry.getExclusive()) {
    for (Entry e : waiting) {
        if (e != entry && e.getExclusive() && overlaps(e, entry)) return false;
    }
}
```

Three details worth stating out loud:

- **Publish before waiting**, not after being granted. The point is to fence readers *while* blocked.
- **Range-aware, not a counter.** A `waitingWriters++` counter is simpler but makes any waiting
  writer block every reader on the file. Checking overlap keeps a writer on `[400, 499]` from
  interfering with readers on `[900, 999]`.
- **The interrupt path must remove and re-signal.** A writer that gives up while fencing readers has
  to un-fence them, or they stay blocked on a writer that no longer exists.

**The cost is the mirror problem.** This is writer preference, so a continuous stream of writers can
starve readers. Unavoidable at this design point — the alternative is full FIFO fairness over all
waiters, which costs reader concurrency. Name the trade rather than letting the interviewer find it.

## One condition, always `signalAll`

Every waiter is waiting for **its own range** to become free. `signal()` wakes an arbitrary one —
most likely not the one whose range just opened up. It rechecks, sleeps again, and the eligible
waiter is never woken. Silent stall, with no wrong-looking line of code.

> `signal()` is only safe when every waiter on a condition waits for the **same** thing.
> Individual predicates ⇒ `signalAll()`, or a condition per waiter.

Third problem in a row where this decides it: `BoundedByteBuffer` (one shared predicate, so `signal`
was merely inefficient), `OrderedChunkAssembler` (N producers, N predicates — `signal` incorrect),
and here (every waiter has its own range — `signal` incorrect). Recognise the shape on sight.

## Identity, not value equality — the bug that looked like good style

`Entry` deliberately does **not** override `equals`/`hashCode`. Adding them — the obvious tidy-up for
what looks like a value class — introduced a silent data race:

```
reader A: granted.add(entryA)   -> set = { [0,10] shared }    size 1
reader B: granted.add(entryB)   -> NO-OP, already "present"   size 1   <- two holders, one element
reader A: close() -> remove     -> set = { }                  size 0   <- B's grant vanished
writer  : nothing conflicts     -> GRANTED, while B is still writing those bytes
```

A `Set` deduplicates. **Two grants of the same range are not duplicates** — they're two independent
holders you have to count. The container encoded a claim about the domain that wasn't true.

And switching to `ArrayList` while keeping value equality does *not* rescue it:
`List.remove(Object)` deletes the *first* element that equals the argument, so thread A's
`waiting.remove(entryA)` can delete thread B's identical entry, stranding A's marker in the set
forever and blocking readers permanently. **Value equality is the problem; the container isn't.**

Two correct options: omit `equals`/`hashCode` (with a comment, or someone re-adds them), or give
`Entry` a unique id and include it so equal-looking grants are genuinely distinct.

This is the same failure class as `BoundedByteBuffer`'s `buf[i] != 0` sentinel — in both cases the
type or structure carried an assumption the data violated.

## Is the linear scan fast enough? Yes, and here's why

The key reframe: **`n` is bounded by concurrency, not by file size.** An entry exists only while
some thread is inside its critical section, so with a 64-thread pool, `n ≤ 64` — on a 500 GB disk,
forever.

Measured cost of a full conflict check:

| n | ns per check |
| ---- | -----------: |
| 8 | 4.1 |
| 32 | 17.7 |
| 128 | 36.4 |
| 1024 | 279.5 |
| 8192 | 2181.1 |

At `n = 32` the whole scan costs less than the `ReentrantLock` acquisition wrapping it. There is no
problem to solve until you have thousands of threads simultaneously inside critical sections, at
which point the scan is not your issue.

**If `n` really were large**, the textbook answer is an **interval tree** — a BST keyed on `start`,
each node augmented with the max `end` in its subtree, giving `O(log n + k)`. Indexed by lock count,
so no dependence on file size. (Special case: with *only* exclusive locks the held ranges are
pairwise disjoint, so a plain `TreeMap` keyed on start suffices — check `floorEntry(s)` plus
`subMap(s, e)`. Shared locks break that, since shared ranges can overlap each other.)

**A segment tree also works**, but note what it's indexed by: the **position space**, not the lock
count. A 500 GB disk is 5×10¹¹ offsets, so it needs a *dynamic* tree with lazily allocated nodes,
~39 levels of pointer chasing, and lazy propagation for the range updates. Cost becomes
`O(log fileSize)` independent of `n` — appealing asymptotically, brutal constants. It only pays off
somewhere past `n ≈ 1000`.

**And the point that actually decides it:** the conflict check and the insert must be atomic
together, so everything funnels through one global lock regardless. The data structure doesn't reduce
contention — it only sets **how long the critical section is**. A cleverer structure with worse
constants makes throughput *worse*, because every thread waits longer for the lock.

**What production actually does:** quantize. Round `[s, e]` out to fixed blocks (4 KB pages, 1 MB
extents) and lock block IDs. Conflict detection becomes a set or bitmap lookup per block touched —
`O(blocks in the request)`, independent of `n`, no tree, no scan. That's page-level locking in
InnoDB and extent locking in filesystems, and it's why arbitrary-interval locking mostly doesn't
exist in real storage engines. The cost is **false conflicts**: two writes to different halves of one
block serialize. Block size is the tuning knob.

## What a range lock will not save you from

```
T1  holds [0,99]     wants [200,299]
T2  holds [200,299]  wants [0,99]
```

Classic deadlock, and the range lock has no idea it's happening. The two standard answers: acquire
all ranges a thread needs in **one call** (so the wait is atomic), or impose a **global ordering** on
acquisition (always take the lower range first). Know both and pick one out loud.

## Known limitations

- **Writer preference starves readers** under a continuous stream of writers. Inherent at this design
  point; FIFO fairness over all waiters is the alternative.
- **No reentrancy.** A thread that already holds `[0, 99]` exclusive and calls `acquire(0, 99, …)`
  again deadlocks against itself. `ReentrantLock`-style ownership tracking would be needed.
- **No upgrade/downgrade.** Shared → exclusive on the same range isn't supported, and adding it
  naively deadlocks when two readers both try to upgrade.
- **No timeout or `tryAcquire`.** A caller can only block indefinitely or be interrupted.
- **`isAllowed` is O(granted + waiting)** per wakeup, and every release wakes everyone, so a release
  storm is O(waiters × n). Fine at realistic `n`; see above for when it isn't.
- The `Entry` javadoc explaining the identity requirement sits at the *bottom* of the class body,
  where it documents nothing — move it above the class declaration so the next person to reach for
  `equals` actually reads it. Unused `java.util.Objects` import.

## Verified

`Test/RangeLockTest.java` — plain `main()`-based, no JUnit. 13 cases, 3/3 clean runs. Every acquire
that might block runs on a daemon thread with a bounded wait, so starvation or a missing signal
**fails** with a message instead of freezing the suite.

Boundary pair, deliberately adjacent in the file:
- `[100,199]` after `[0,99]` — adjacent, must be granted
- `[99,150]` after `[0,99]` — shares one byte, must block

Plus: disjoint ranges, overlapping read-waits-for-writer and write-waits-for-reader, 8 shared readers
concurrent, **identical shared ranges released independently** (the `equals` regression), a
single-byte range inside a write, a waiter overlapping two held ranges, idempotent close, invalid
arguments, and writer-not-starved.

The stress case is the one that finds real bugs: 16 threads × 400 random ranges, each holder
recording its range on entry and asserting **no conflicting range is already recorded**. That checks
the mutual-exclusion invariant itself — a lock that granted everything would pass a deadlock-only
test.

> Worth remembering: while the `equals`/`hashCode` race was live, the suite still printed
> **ALL PASS**, because the identical-range case wasn't in it yet. A green suite bounds what you
> tested, not what's correct.

## 30-second recall

> Two collections under one lock: **granted** and **waiting**. Admissible = nothing granted conflicts,
> and (if shared) no waiting writer overlaps. Conflict = **overlap AND at least one exclusive**.
> Closed ranges: `a.start <= b.end && b.start <= a.end` — `<` instead of `<=` lets a one-byte overlap
> through, which is a data race, not a slowdown. **Starvation is liveness, not throughput**: publish
> the waiter *before* `await` so a blocked writer fences new readers, remove-and-re-signal on
> interrupt, and accept that this is writer preference. **`signalAll` always** — every waiter has its
> own range, so `signal` wakes the wrong thread. **`Entry` must be identity, never value** — a `Set`
> with `equals` collapses two holders of the same range into one element and a single `close()` frees
> both; and `List` doesn't fix it, because `remove(Object)` deletes the first *equal* element. Handle
> instead of `release(start,end)` because value-identical grants are otherwise indistinguishable.
> Linear scan is right: `n` is bounded by **concurrency** (~18 ns at n=32), interval tree past ~1000,
> and real storage engines sidestep it entirely by quantizing to fixed blocks.
