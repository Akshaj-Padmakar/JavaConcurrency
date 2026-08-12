# Range Lock Manager

> Interview form: _"Multiple threads read and write different parts of one big file. Don't let them
> block each other unless they actually overlap."_
> Every lock you've built so far guards **one thing**. This one guards an unbounded set of
> **intervals**, and which of them conflict is decided at request time.

## What you're building

A VM is booted directly off a snapshot — Live Mount — while the restore is still streaming data in.
So one virtual disk image has, at the same moment:

- **writers** filling in ranges as chunks arrive from the backup, and
- **readers** serving the running VM's I/O out of ranges that are already there.

A single lock over the whole disk would serialize a 500 GB restore against every guest read. Useless.

The rule you actually need:

> Two operations may run concurrently unless their byte ranges **overlap** and at least one of them
> is a **write**.

Reader + reader on the same range: fine. Reader + writer on disjoint ranges: fine. Reader + writer
overlapping by one byte: must wait.

## A tiny example

**Ranges are closed: `[start, end]` includes both endpoints.** `[0, 99]` is the first 100 bytes.
(Same convention as an HTTP `Range: bytes=0-99` header. Callers working from an offset and a length
pass `offset` and `offset + length - 1`.)

```
T1  h1 = acquire [0, 99]    WRITE   -> granted
T2  h2 = acquire [200, 299] WRITE   -> granted        disjoint from T1
T3       acquire [50, 150]  READ    -> BLOCKS         overlaps T1's write
T4  h4 = acquire [100, 199] READ    -> granted        adjacent to T1, shares no byte
T5       acquire [99, 150]  READ    -> BLOCKS         shares exactly one byte (99) with T1
T6       acquire [250, 260] READ    -> BLOCKS         inside T2's write
T1  h1.close()                      -> T3, T5 granted
```

Note there is no public `release(start, end)` — releasing happens through the **handle** that
`acquire` returned. See below for why.

`T4` and `T5` are the pair to get right, and they differ by one byte. `[0, 99]` and `[100, 199]`
are adjacent and share nothing; `[0, 99]` and `[99, 150]` share byte 99 and must serialize. Use
`<` where you needed `<=` and every single-byte overlap slips through — a data race that shows up
as rare corruption, not as a hang.

With closed ranges `start == end` is a legitimate **one-byte** range, not an empty one. Empty ranges
simply aren't expressible, which removes an edge case rather than creating one.

## What you implement

```java
class RangeLock {
    /** Blocks until no conflicting range is active. Close the handle to release. */
    Handle acquire(long start, long end, boolean exclusive) throws InterruptedException;

    interface Handle extends AutoCloseable {
        @Override void close();   // no checked exception -- usable in try-with-resources
    }
}
```

A handle rather than `unlock(start, end)` so a caller can't release a range they never took, or
release the wrong one.

```java
try (RangeLock.Handle h = lock.acquire(offset, offset + len - 1, true)) {   // closed: -1
    storage.write(offset, data);
}
```

## Constraint

`Thread`, `synchronized`, `wait`/`notify`, or `Lock` + `Condition`. Track the active ranges in your
own structure — a `List`, a `Map`, whatever you can defend. No `java.util.concurrent` locks doing the
conflict detection for you.

## Requirements

- **Correct conflict rule.** Overlapping + at least one exclusive ⇒ serialize. Everything else runs
  concurrently.
- **Shared ranges are genuinely concurrent.** N readers over the same range must all hold it at once,
  not queue up.
- **Release is exact.** Closing one handle must not free anybody else's range, including an identical
  range held by another thread.
- **No starvation.** A steady stream of readers must not postpone a waiting writer forever.
- **Idempotent close.** Closing a handle twice must not corrupt the active set.

## Edge cases

Single-byte range (`start == end`) · `end < start`, which is malformed and must be rejected ·
negative offsets · ranges that share exactly one byte at either end · two threads holding the *same*
shared range and one releasing · a waiter whose range overlaps several active ranges at once · a
range that spans the entire file · thousands of active ranges.

## Three questions to answer before you code

**How do you detect a conflict?** With `n` ranges active, the obvious answer is a linear scan.
When does that stop being acceptable, and what would you reach for instead?

**Who do you wake on release?** Every waiter is waiting for a *different* range to become free — so
this is the `signalAll` situation from the assembler, not the `signal` one. Convince yourself why,
then ask the harder version: can you wake only the waiters whose ranges actually overlap the one you
just released?

**How do you stop readers starving a writer?** Readers overlapping each other are all admissible, so
a continuous stream of them means the conflict test never comes up false for the writer. What extra
state makes a *waiting* writer block *new* readers — and what does that cost the readers?

## What this problem will not save you from

Two threads each holding one range and requesting the other still deadlock:

```
T1  holds [0,100)    wants [200,300)
T2  holds [200,300)  wants [0,100)
```

A range lock has no idea this is happening. Know the standard answers — acquire all ranges in one
call, or impose a global ordering on acquisition — and be ready to say which you'd pick.

## The jargon

| Plain version | The term |
| ------------- | -------- |
| Reads share, writes exclude | **shared / exclusive** locking, a.k.a. readers-writer |
| Only overlapping operations serialize | **fine-grained** or **range locking** (vs. a coarse global lock) |
| Waiting until nothing conflicting is active | **conflict-based admission** |
| Readers forever postponing a writer | **writer starvation** |
| The set of ranges currently held | the **lock table** |
| Finding which held ranges overlap a request | an **interval overlap query** — an interval tree does it in O(log n + k) |
