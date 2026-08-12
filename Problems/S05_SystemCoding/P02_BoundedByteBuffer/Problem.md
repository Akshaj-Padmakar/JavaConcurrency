# Bounded Circular Byte Buffer

> Interview form: _"Implement a thread-safe bounded buffer that one thread writes bytes into and
> another reads bytes out of."_ It looks like a blocking queue. The differences are where the
> problem lives.

## What you're building

A fixed-size pipe between two threads.

One thread reads a file off disk and pushes bytes in. Another pulls bytes out and sends them to the
backup target over the network. The buffer sits between them and is a **fixed size** — say 1 MB.

The size is the point. If the network is slower than the disk, the buffer fills up and the reader
**blocks** until space frees. That's not a failure, it's the feature: without it, a fast disk feeding
a slow network buffers the whole 100 GB file in RAM and the process dies. A full buffer pushing back
on the producer is called **backpressure**, and a bounded buffer is how you get it for free.

Symmetrically, if the reader gets ahead, the writer thread blocks on an empty buffer instead of
spinning.

## A tiny example

Capacity 8. `_` is unused space.

```
                          array               head  tail  count
write("HELLO")     [ H E L L O _ _ _ ]          0     5      5
read(3) -> "HEL"   [ _ _ _ L O _ _ _ ]          3     5      2
write("WORLD")     [ L D _ L O W O R ]          3     2      7
                     ^^^         ^^^^^
                     wrapped     written from tail=5 onward
read(7) -> "LOWORLD"
```

That third step is the whole reason it's called circular: `WORLD` didn't fit contiguously at the end,
so `W O R` went to indices 5–7 and `L D` wrapped to 0–1. The bytes stay in order; only the storage
wraps.

## What you implement

```java
class BoundedByteBuffer {
    BoundedByteBuffer(int capacity);

    /** Writes all len bytes. Blocks while the buffer is full. */
    void write(byte[] src, int offset, int len) throws InterruptedException;

    /** Reads up to len bytes. Blocks while empty. Returns count read, or -1 at end of stream. */
    int read(byte[] dst, int offset, int len) throws InterruptedException;

    /** No more writes. Readers drain what's left, then see -1. */
    void close();

    int size();
}
```

## The semantics you have to pin down

These are the decisions an interviewer is watching for. Pick one of each and say it out loud.

**`write` is all-or-nothing, `read` is best-effort.** `write(5 bytes)` into a buffer with 3 free
doesn't write 3 and return — it blocks until all 5 are in. But `read(1024)` with 10 bytes available
returns those 10 immediately rather than waiting for 1024. This asymmetry is what real streams do,
and it matters: a reader that waits for a full buffer would deadlock against a producer that has
nothing more to send.

**`close()` is how the stream ends.** Without it a reader blocks forever once the producer is done —
the same termination problem as the thread pool's poison pill, in a different costume. After
`close()`: writes fail, reads drain the remaining bytes, and only then return `-1`.

## Constraint

`Thread`, `synchronized`, `wait`/`notify` (or `Lock` + `Condition`). Build the buffer on a flat
`byte[]`.

**Not** `BlockingQueue<Byte>` — every byte boxed into an `Integer`-like object is 16+ bytes plus a
queue node, so a 1 MB buffer costs ~50 MB and destroys cache locality. Wrapping
`java.util.concurrent` here would also be answering the question with the question.

## Requirements

- **Thread-safe** for concurrent readers and writers. No torn data, no lost bytes, no duplicates.
- **Bytes come out in the order they went in.**
- **Blocking, not spinning.** A full `write` and an empty `read` must park, not busy-wait.
- **Fixed memory.** One `byte[capacity]`, allocated once. No growth, no per-byte objects.
- **Wrap-around correctness** — data spanning the end of the array.
- **Clean shutdown.** `close()` releases every blocked reader; nothing hangs.

## Edge cases

`len` larger than capacity (can it ever succeed?) · `len == 0` · reading an empty buffer that is
already closed · closing while writers are blocked · `write` after `close` · capacity of 1 ·
negative offset/len · `offset + len` past the end of the caller's array.

## Two questions to have an answer for

**How do you tell full from empty?** With only `head` and `tail`, `head == tail` means both. Three
standard fixes, and they trade differently — know which you picked and why.

**Is `notify()` enough, or do you need `notifyAll()`?** In a one-item-at-a-time blocking queue, one
`put` frees exactly one waiting `take`, so a single `signal` is provably sufficient. Here a write can
free a variable number of bytes, and waiters need *different* amounts. Work out whether a single
`notify` can wake a thread that still can't proceed while leaving one that could asleep — and what
that costs you.

That second one is the difference between this and the blocking queue you already built in
[P06](../../S00_General/P06_ThreadPoolWithShutdown/Problem.md). It's the reason this problem is on
the list.
