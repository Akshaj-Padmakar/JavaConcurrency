# Ordered Chunk Assembler — Revision Sheet

> See [Problem.md](Problem.md). Implementation: `OrderedChunkAssembler.java`,
> `Test/OrderedChunkAssemblerTest.java`.

## One-line idea

Many producers deposit chunks by sequence number, out of order. One consumer takes them **in** order.
Memory stays bounded because `put` blocks whenever a sequence is more than `windowSize` ahead of what
the consumer still needs — backpressure keyed on **position**, not on count.

## State model

| Field          | Meaning                                                              |
| -------------- | -------------------------------------------------------------------- |
| `currentId`    | next sequence the consumer needs — the low-water mark                |
| `buffer`       | chunks that arrived early, keyed by sequence                         |
| `windowSize`   | how far ahead of `currentId` a producer may run                      |
| `finished` / `lastSequence` | the stream's declared end                              |
| `failure`      | a chunk that can never be fetched; poisons the whole stream          |
| `lock` + `putCondition` / `takeCondition` | one lock, two wait sets                    |

## The window must be anchored on what the consumer *needs*

```java
while (currentId + windowSize - 1 < sequence && failure == null) {
    putCondition.await();
}
```

Admits `[currentId, currentId + windowSize - 1]` — exactly `windowSize` slots.

The tempting wrong answer is to anchor on the **highest sequence seen so far**. That gives no bound
at all: every arrival raises the ceiling, so producers never block and one slow fetch buffers the
rest of the file. Anchoring on `currentId` means the bound is enforced by the thing that actually
frees memory — the consumer draining.

Which makes `take()` the only event that can slide the window, and therefore the only place that can
release a blocked producer:

```java
byte[] ans = buffer.remove(currentId++);
putCondition.signalAll();          // the window just slid
```

## `take()` has three exits, and the order matters

```java
if (buffer.get(currentId) != null) { ...; return chunk; }   // 1. data in hand
if (failure != null) { throw ...; }                          // 2. stream broken
return null;                                                 // 3. stream complete
```

**Data first, always.** `finished` and `failure` are statements about the stream's *future*; a
buffered chunk is a fact about its *past*. Facts win.

- Check `finished` first and a `finish()` called early would truncate the stream — silently losing
  every chunk still in flight.
- Check `failure` first and a break at chunk 37 would discard the perfectly valid chunks 30–36 that
  are already buffered, *and* lose the information about where the break actually is. Draining first
  gives the consumer the **longest valid contiguous prefix** and then an error at exactly the gap —
  which is what makes a checkpoint-and-resume possible.

It cannot hang: once `failure != null` the wait predicate is false on every call, so the consumer
drains the prefix at full speed and throws at the first real gap. And `put` rejects new chunks once
failed, so nothing sneaks in behind it.

This is the same rule as `BoundedByteBuffer.close()` (drain, then `-1`) and
`ExecutorService.shutdown()` (run what's queued, then exit). A terminal signal stops *new* work; it
never discards work already accepted. It's also why `shutdownNow()` is a **separate method** — "stop
and discard" is a different operation, not a reordering of the same checks.

## `finish` is a declaration, `fail` is a poison — and they are not the same

**`finish(lastSequence)` does not mean "I have sent everything."** It means *"this stream ends at
sequence N"* — a statement about the shape of the stream, true the moment you read the manifest:

```java
List<ChunkRef> chunks = manifest.chunksFor(file);
asm.finish(chunks.size() - 1);                       // legal RIGHT HERE
for (...) pool.execute(() -> asm.put(seq, fetch(seq)));   // nothing fetched yet
```

That's why it takes a parameter instead of being a no-arg `close()`. With out-of-order producers,
"I submitted everything" and "everything arrived" are different moments; a no-arg close would have to
join all 5000 fetches first. The parameter decouples them.

And `finish` does **not** release a consumer waiting on a gap. `take()` returns `null` only when
`currentId > lastSequence` — after every gap below the end has filled.

| | set by | `take()` behaviour | means |
| --- | ------ | ------------------ | ----- |
| **normal end** | `finish(N)`, by the coordinator | blocks on gaps, returns `null` after draining N | stream complete |
| **broken** | `fail(cause)`, by the worker whose fetch died | throws immediately at the gap | stream unusable |

Two methods because they are genuinely different states. `BoundedByteBuffer` only needs `close()` —
a local pipe can't half-fail. A distributed fetch can, and collapsing them would make a failed
restore look like a successful short one, which for a backup product is the worst possible bug.

`fail` is also the **only** escape from a permanent gap: `finish(4999)` doesn't help when chunk 37 is
unfetchable, because `37 ≤ 4999` and the predicate keeps waiting.

One failed chunk kills the whole stream, and that's correct — the consumer is a stateful ordered
pipeline (decompress → decrypt → socket). You cannot skip chunk 37 and carry on; everything after it
would be garbage.

## `signal` vs `signalAll` — the asymmetry is the point

| Condition | Waiters | Predicate | Correct call |
| --------- | ------- | --------- | ------------ |
| `takeCondition` | one consumer | one shared predicate | `signal()` suffices |
| `putCondition` | N producers | **N distinct predicates** — each waits for *its own* sequence | `signalAll()` **required** |

When the window slides by one, exactly one new sequence becomes admissible. `signal()` wakes an
*arbitrary* producer — most likely not the one holding that sequence. It rechecks, finds itself still
too far ahead, sleeps again, and the eligible producer is never woken. Silent stall, no wrong-looking
line of code.

Sharper than the `BoundedByteBuffer` case: there every reader waited on `cnt > 0`, one shared
predicate, so `signal()` was merely inefficient. Here it is outright **incorrect**.

> **`signal()` is only safe when every waiter on that condition is waiting for the same thing.**
> Individual predicates ⇒ `signalAll()`, or a condition per waiter.

## Bug log: the same rule, three times

Every deadlock found in this class had one cause — **state changed, nobody signalled**:

1. **`take()` never signalled `putCondition`.** Advancing `currentId` is the only event that slides
   the window, so a producer blocked on it could never be released. Deterministic deadlock.
2. **`finish()` never signalled `takeCondition`.** A consumer already parked was waiting on the exact
   predicate `finish` mutates. **This one was a race** — it passed six small tests and hung only the
   8-worker stress test, because it needs the consumer to park *before* `finish` lands. Passes the
   unit tests, deadlocks in production.
3. (In `BoundedByteBuffer`, the same thing: `notifyAll()` placed after a loop the writer parked
   inside.)

> **If you change state that someone might be waiting on, signal before you release the lock.**

## Dispatch discipline: a constraint on the *caller*, not the class

The first stress test deadlocked with a correct assembler. Each of 8 workers got a **shuffled** list
of sequences; worker 0 drew `[5000, 3, …]`, blocked on `put(5000)` waiting for the window — but
sequence **3** was also worker 0's job, later in its list. The consumer needed 3. Nobody could
deliver it.

> **Producers must dispatch in roughly increasing sequence order.** If a worker can block on a
> far-ahead chunk while still owing one the consumer needs, you deadlock — and no window size saves
> you.

The fix is what real pools do: take the next sequence from a **shared counter**, so the lowest
outstanding chunk is always in flight. Completion stays out of order — that's the whole point; only
*dispatch* is ordered.

Corollary: **`windowSize` must exceed the number of concurrent fetchers.** With in-order dispatch the
highest in-flight sequence is about `currentId + concurrency`, so a narrower window leaves every
worker blocked with none free to fetch what's needed.

## Head-of-line blocking is inherent, not a defect

One missing chunk stalls the consumer even when the next twenty are sitting in the buffer. That's
what an ordered stream *is*. Say it out loud rather than apologising for it — the alternatives are to
give up ordering (then you don't need this class) or to give up boundedness (then one slow fetch
OOMs you).

## Known limitations

- **The failure cause is dropped.** `fail(Throwable cause)` stores it, but both throw sites raise a
  bare `IllegalStateException(String)`. Use `IllegalStateException(msg, failure)` — for a restore
  that dies unattended, the chain *is* the diagnosis.
- **A producer past the declared end can park forever.** The `finished && sequence > lastSequence`
  guard runs only on entry, not after waking. A `put(100)` that entered before `finish(50)` stays
  blocked on a window that will never slide again. Fold the condition into the wait predicate.
- **`windowSize` is not validated.** `<= 0` makes the predicate `currentId - 1 < sequence`, blocking
  every legal sequence from the start.
- **`null` is the presence marker** (`buffer.get(currentId) != null`). Safe only because `put`
  rejects null chunks — a guarantee enforced at a distance. `containsKey` is the local argument.
- **`lastSequence` defaults to `0`** rather than `Long.MAX_VALUE`. Harmless (every read is guarded by
  `finished`) but it reads as "ends at 0" instead of "unbounded until declared".
- **A duplicate sequence *inside* the window silently overwrites** the earlier chunk. Fine if
  retries are idempotent; worth rejecting if not.
- `buffer` should be `private final`; unused `java.io.IOException` import.

## Verified

`Test/OrderedChunkAssemblerTest.java` — plain `main()`-based, no JUnit. 10 cases, 3/3 clean runs.
Every blocking test uses daemon threads with a bounded `join`, so a missing signal **fails** rather
than freezing — which is how bug #2 above was caught.

- scrambled arrival `{3,0,4,1,2}` drains as `01234`
- far-ahead producer blocks on the window, releases when the consumer slides it
- `finish()` does not truncate chunks already buffered
- **`finish()` wakes a consumer that is already parked** (the race regression)
- `fail()` releases both a blocked producer and a blocked consumer
- `fail()` still delivers the valid prefix `012` *before* throwing at the gap
- stale sequence dropped after being consumed
- window of 1 — full lockstep, 50 chunks in order
- null chunk / negative sequence / sequence past the declared end all rejected
- **8 fetchers × 1000 chunks × 5 trials**, dispatched in order from a shared counter, completing out
  of order with randomised latency

## 30-second recall

> N producers out of order, 1 consumer in order, bounded by a **window anchored on `currentId`** —
> anchor on the highest sequence seen and there is no bound at all. `take()` is the only thing that
> slides the window, so it must `signalAll(putCondition)`. **`take()` checks data before terminal
> state** — drain, then EOF, then failure — so `finish` can't truncate and `fail` still yields the
> longest valid prefix plus the exact break point. **`finish(N)` is a declaration**, callable before
> anything arrives, and does *not* release a consumer sitting on a gap; **`fail` is a poison** and is
> the only escape from a gap that will never close. `signal()` for the single consumer,
> **`signalAll()` for producers** — they wait on N distinct predicates, so a single signal wakes the
> wrong one. Callers must **dispatch in increasing order** from a shared counter and set
> `windowSize > concurrency`, or a worker blocks on a far-ahead chunk while still owing the needed
> one. Head-of-line blocking is inherent to ordering, not a bug.

---

# Refactor: `byte[][]` slots instead of `Map<Long, byte[]>`

The window bound makes the map unnecessary — and the map is the slow part.

## Why it works

Only sequences in `[currentId, currentId + windowSize - 1]` are ever buffered. Any two of them
differ by less than `windowSize`, so `sequence % windowSize` is **collision-free across the whole
window**. A plain array indexed by that remainder is a perfect hash, for free:

```java
private final byte[][] slots;     // length windowSize, allocated once

public OrderedChunkAssembler(int windowSize) {
    if (windowSize <= 0) throw new IllegalArgumentException("windowSize must be > 0");
    this.windowSize = windowSize;
    this.slots = new byte[windowSize][];
}

private int slotOf(long sequence) {
    return (int) (sequence % windowSize);
}
```

`put`, after the window wait and the stale-sequence drop:

```java
slots[slotOf(sequence)] = chunk;
takeCondition.signal();
```

`take`:

```java
int slot = slotOf(currentId);
while (slots[slot] == null && failure == null && !(finished && currentId > lastSequence)) {
    takeCondition.await();
    slot = slotOf(currentId);          // currentId cannot move here (single consumer), but recompute
}                                       // anyway so the code survives a second consumer

if (slots[slot] != null) {
    byte[] chunk = slots[slot];
    slots[slot] = null;                // clearing IS the removal
    currentId++;
    putCondition.signalAll();
    return chunk;
}
```

Nothing else in the class changes — the window predicate, the signalling, `finish`, and `fail` are
all untouched. Only the storage swaps out.

## What it buys

Measured, 20 M deposit/presence-check/take cycles, single-threaded:

```
HashMap<Long,byte[]> :  176 ms    (9 ns/chunk)
byte[][] slots       :   14 ms    (1 ns/chunk)
speedup              :  12.2x
```

Where the 8 ns goes:

- **Two allocations per chunk.** `Map<Long, byte[]>` boxes every key into a `Long` (the
  `Long.valueOf` cache only covers −128..127, so real sequence numbers always allocate) *and*
  allocates a `HashMap.Node`. That's ~48 bytes of garbage per chunk — 10,000 allocations for a
  5,000-chunk file, all of it pure bookkeeping.
- **Hashing and pointer chasing** on every `get`/`put`/`remove`, versus one array index.
- **Cache behaviour.** `slots` is a contiguous block of references sized to the window; map nodes are
  scattered across the heap.

Memory is also exact rather than amortised: `windowSize` references, allocated once, no table, no
resize, no load factor.

If you make `windowSize` a power of two, `sequence % windowSize` becomes `sequence & (windowSize - 1)`
— worth doing, since the window size is arbitrary anyway and integer division is the most expensive
operation left in the hot path.

## What it costs

**The window guards become correctness-critical, not just memory-critical.** With the map, a stray
out-of-window sequence merely leaks. With the array it lands on `slot(sequence % windowSize)` and
**silently overwrites a live chunk** belonging to a different sequence. So these two lines stop being
hygiene and start being load-bearing:

```java
while (currentId + windowSize - 1 < sequence && ...) putCondition.await();   // not too far ahead
if (sequence < currentId) return;                                            // not already consumed
```

That is the real trade: the array is faster *because* it trusts an invariant the map didn't need.
Say that out loud rather than just claiming the speedup.

**`null` is again the presence marker**, and this time you can't fall back on `containsKey`. It stays
safe only because `put` rejects null chunks — the same guarded-at-a-distance argument as
`BoundedByteBuffer`'s `cnt`. If null chunks ever became legal you'd need a parallel `boolean[]
present`, or a sentinel `byte[]` instance.

**Fixed window.** A map lets you resize the window at runtime; the array doesn't without a rebuild.
Not a concern here — `windowSize` is set once — but it's the reason a general-purpose resequencer
might keep the map.

**Debuggability.** `buffer.toString()` shows you exactly what's outstanding. `slots` shows you a
window's worth of references with no sequence numbers attached, and you have to reconstruct which is
which from `currentId`.

## Which to pick

For an interview: **write the map first, mention the array.** The map is obviously correct with no
invariant to argue, and the sentence

> "The window guarantees every buffered sequence is within `windowSize` of `currentId`, so
> `sequence % windowSize` is collision-free — I'd index a `byte[][]` directly and skip the boxing and
> the node allocation entirely."

demonstrates you understand *why* the window exists, which is worth more than the 12x.
