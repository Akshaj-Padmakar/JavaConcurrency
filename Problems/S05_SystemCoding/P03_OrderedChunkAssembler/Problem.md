# Ordered Chunk Assembler

> Interview form: _"You're fetching a file's chunks in parallel. The consumer needs them in order."_
> The reordering is easy. Doing it in **bounded memory** is the problem.

## What you're building

Restoring a backup. The file's chunks live scattered across storage nodes, so you fetch them with a
pool of workers — 32 fetches in flight, because each one is a slow network round trip and doing them
one at a time would take hours.

They come back **out of order**. Chunk 7 lands before chunk 3 because node 7 was less busy.

For most of a restore that's fine: with positional writes each worker just `pwrite`s its chunk at its
own offset and order never matters. But sometimes the destination is a **stream**, not a file — you're
piping the restore through decompress → decrypt → socket, and every one of those stages is stateful.
Feed them chunk 7 before chunk 3 and you get garbage.

So: **many workers producing out of order, one consumer that must receive strictly in order.**

## The part that makes it hard

Buffer everything until the gaps fill, and chunk 3 being slow means chunks 4…5000 pile up in memory.
That's a 40 GB heap for a file you were streaming precisely to avoid loading.

So the assembler has a **window**. It will hold a bounded number of out-of-order chunks, and a worker
trying to deposit a chunk too far ahead of the gap must **block** until the gap closes.

That's backpressure again — but keyed on *position*, not on total count. A worker isn't blocked
because the buffer is full; it's blocked because it's running too far ahead of the slowest fetch.

## A tiny example

`windowSize = 3` — at most 3 chunks may sit buffered ahead of what the consumer still needs.

```
                                   buffered      nextNeeded   consumer gets
put(2, "CC")     ok, 2 < 0+3       {2}                0
put(0, "AA")     ok                {0,2}              0
take()           -> "AA"           {2}                1
put(4, "EE")     ok, 4 < 1+3       {2,4}              1
put(5, "FF")     BLOCKS  5 >= 1+3  {2,4}              1        <- too far ahead
put(1, "BB")     ok                {1,2,4}            1
take()           -> "BB"           {2,4}              2
take()           -> "CC"           {4}                3
                 put(5) unblocks   {4,5}              3        <- window slid forward
take()           BLOCKS            {4,5}              3        <- chunk 3 hasn't arrived
```

Two different blocks, for opposite reasons:

- **`put` blocks** when a producer is too far ahead of the gap — memory protection.
- **`take` blocks** when the next chunk in sequence hasn't arrived yet — ordering.

Note the last line: **one missing chunk stalls the consumer even though 4 and 5 are sitting right
there.** That's head-of-line blocking, and it's inherent to an ordered stream, not a bug in your
design. Be ready to say so.

## What you implement

```java
class OrderedChunkAssembler {
    OrderedChunkAssembler(int windowSize);

    /** Deposit a chunk. Blocks while sequence is too far ahead of what the consumer still needs. */
    void put(long sequence, byte[] chunk) throws InterruptedException;

    /** Next chunk in sequence order. Blocks until it arrives. Returns null at end of stream. */
    byte[] take() throws InterruptedException;

    /** No chunk after lastSequence will ever arrive. */
    void finish(long lastSequence);

    /** A fetch failed permanently: fail the stream instead of stalling on a gap forever. */
    void fail(Throwable cause);
}
```

## Constraint

`Thread`, `synchronized`, `wait`/`notify`, or `Lock` + `Condition`. No `java.util.concurrent`
collections — `PriorityBlockingQueue` or a `ConcurrentSkipListMap` would be answering the question
with the question. A plain array or `HashMap` under your own lock is the point.

## Requirements

- **Strict order out.** `take()` returns sequences 0, 1, 2, … with no gaps and no duplicates.
- **Bounded memory.** At most `windowSize` chunks buffered, regardless of how far ahead producers
  run or how long one fetch stalls.
- **Concurrent producers.** Many workers call `put` at once, with arbitrary sequence numbers.
- **Clean termination.** After `finish(last)` and the consumer draining through `last`, `take()`
  returns `null`. No producer or consumer left parked.
- **Failure propagates.** `fail(cause)` must wake everyone — a permanently failed fetch must surface
  as an error, not as a consumer blocked forever on a gap that will never close.

## Edge cases

Duplicate sequence (a retried fetch delivering twice) · a sequence below `nextNeeded` (already
consumed) · `finish()` called while producers are still blocked · `finish()` with a `lastSequence`
that never arrives · window of 1 · out-of-range or negative sequence · `take()` on a stream that was
finished but is empty.

## Two questions to answer before you code

**What holds the buffered chunks?** You need "is sequence N present?" and "give me sequence
`nextNeeded`" to both be cheap. A `HashMap<Long, byte[]>` works. So does a plain
`byte[][] slots = new byte[windowSize][]` indexed by `sequence % windowSize` — which is your ring
buffer again, one level up, storing chunks instead of bytes. What does the ring give you that the
map doesn't, and what does it cost?

**Where does the window bound actually come from?** `put` must reject-and-block when the sequence is
too far ahead. Too far ahead of *what* — the last chunk taken, the next one needed, or the highest
one buffered? Only one of those makes the memory bound actually hold.

## The jargon

| Plain version | The term |
| ------------- | -------- |
| Chunks arrive out of order, must leave in order | **reordering buffer** / resequencer |
| Only N chunks may be buffered ahead of the gap | **sliding window** — the same idea as TCP's receive window |
| One missing chunk stalls everything behind it | **head-of-line blocking** |
| A producer running too far ahead gets parked | **backpressure**, keyed on position rather than count |
| The consumer's next required sequence | the **low-water mark** |
