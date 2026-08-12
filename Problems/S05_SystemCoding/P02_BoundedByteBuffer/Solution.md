# Bounded Circular Byte Buffer — Revision Sheet

> See [Problem.md](Problem.md). Implementation: `BoundedByteBuffer.java`,
> `Test/BoundedByteBufferTest.java`.

## One-line idea

A fixed `byte[]` plus **two integers**: `readIndex` (where the live bytes start) and `cnt` (how many
there are). The array is pure storage — no cell ever encodes anything about occupancy. `write`
blocks while `cnt == capacity`, `read` blocks while `cnt == 0`, and `close()` is what lets a reader
learn the stream has ended rather than waiting forever.

## State model

| Field       | Meaning                                                                    |
| ----------- | -------------------------------------------------------------------------- |
| `buf`       | `byte[capacity]`, allocated once. Stores bytes and nothing else             |
| `readIndex` | index of the first live byte                                               |
| `cnt`       | number of live bytes — **the only record of occupancy**                     |
| `closed`    | no more writes; readers drain, then see `-1`                                |
| `lock` + `notFull`/`notEmpty` | one `ReentrantLock`, two wait sets                       |

Everything else is derived:

```java
writeIndex = (readIndex + cnt) % capacity;   // never stored
isFull     = cnt == capacity;
isEmpty    = cnt == 0;
freeSpace  = capacity - cnt;
```

`writeIndex` is deliberately *not* a field. Three indices means three things to keep consistent;
two is enough.

## Never encode metadata in-band

The first version used `buf[i] != 0` to mean "occupied". That is the defining mistake of this
problem, and it is not fixable by choosing a better sentinel:

```
/bin/ls, 154,624 bytes:
  0x00 appears 107,104 times
  0xFF appears   1,834 times
  distinct byte values present: 256 of 256
```

**All 256 byte values occur in one ordinary binary.** There is no value to reserve. A payload of
`{1, 2, 0, 3}` delivered `{1, 2, 0, ...}` and then the reader parked forever on the real zero,
believing the buffer empty.

Widening to `int[]` (0..255 data, −1 empty) *would* give you a sentinel, and costs everything the
flat array was for:

| Approach | Extra memory for a 1 MB buffer | `System.arraycopy` | Works with `InputStream`/sockets |
| -------- | ------------------------------ | ------------------ | -------------------------------- |
| sentinel in `int[]` | +3 MB | no | no |
| `cnt` field | **+4 bytes** | yes | yes |

Java already solves this the same way: `InputStream.read()` returns `int`, not `byte`, precisely so
`-1` can mean EOF without colliding with the legitimate byte `0xFF`. When your sentinel lives inside
the value domain, move it out of the value domain.

## `write` is all-or-nothing, `read` is best-effort

Not an arbitrary asymmetry — each side has a reason, and both match `java.io`.

**`read` must be best-effort.** A producer sends 10 bytes then closes; a consumer with a 1 KB buffer
calls `read(dst, 0, 1024)`. If read waited for 1024 it would wait forever — the reader's own buffer
size would become a contract the producer never agreed to. So it returns what's there (always ≥ 1,
or `-1`), and the caller loops on the returned count. Same as `InputStream.read(byte[],int,int)`.

**`write` must loop.** It returns `void`, so there's no way to tell the caller "I only took 40 of
your 100 bytes" — silently dropping 60 is data loss. Same as `OutputStream.write`.

**And the loop must take what fits, not wait for the whole `len`.** Waiting for all `len` bytes to
fit deadlocks on `write(100)` into a capacity-4 buffer — 100 free bytes never exist no matter how
fast the reader drains. Taking `Math.min(len - written, capacity - cnt)` each pass handles it
naturally, and that case is a test.

If you want strict "exactly `len`", layer it — `readFully` as a loop over `read`, the way
`DataInputStream` does. Keep the "wait forever for bytes that will never come" failure out of the
primitive, where it would be unfixable.

## Never park while holding news

The bug that took longest to find. `notifyAll()` sat *after* the copy loop, but `wait()` sat
*inside* it:

```java
while (written < len) {
    while (cnt == capacity) wait();   // parks here, mid-write
    ... cnt += chunk;                 // state changed -- nobody told
}
notifyAll();                          // unreachable if the loop parks
```

Capacity 4, reader asks for 2 and parks on empty. Writer arrives with 6 bytes, deposits 4, finds the
buffer full, and parks — **having announced nothing.** The reader is asleep on `cnt == 0`, which is
no longer true. Both threads wait forever, and there is no obviously wrong line of code.

> **Rule: any `wait()` reachable after you have mutated shared state needs a signal in front of it.**

The chunked structure satisfies this for free: `signalAll()` is the last statement of the loop body
and `await()` is the first, so between any state change and any subsequent park, a signal has run.
State that invariant in one sentence if asked — it's the whole correctness argument.

## `toEnd` is a copy length, never an accounting quantity

The subtlest bug, and it produced the **right byte count with wrong bytes**:

```
sent    : ABCDEFGHIJKLMNOPQRSTUVWXYZ
received: ABCDEFGH LM FGH NOPQRSTUVWXYZ
                ^^    ^^^
                IJK lost   FGH resurrected
```

Cause — in `read`, `cnt -= toEnd` and `return toEnd` where both should be `chunk`:

- `chunk` is what you copied (**both** `arraycopy` calls). `toEnd` is only the pre-wrap part.
- `return toEnd` → you copied `chunk` into the caller's array but reported `toEnd`; the difference is
  consumed from the buffer and never delivered. That's the lost `IJK`.
- `cnt -= toEnd` → `cnt` stays inflated, so already-read cells look live and get handed out again.
  That's the resurrected `FGH`. It also poisons `writeIndex = (readIndex + cnt) % capacity`, and
  `cnt` drifts upward until it pins at `capacity` and the writer blocks forever.

The tell was inside the same method: `readIndex += chunk` on one line, `cnt -= toEnd` two lines
later. **Same operation, two different quantities.** `write` had it right — `cnt += chunk`,
`written += chunk`, with `toEnd` used only as an `arraycopy` length.

## `arraycopy`, not a byte loop

Measured on the same ring, 512 MB through a 1 MB buffer:

```
per-byte loop   :  909 ms      563 MB/s
System.arraycopy:    5 ms   93,912 MB/s      167x
```

`arraycopy` is a **JIT intrinsic** — a hardware `memmove` using SIMD registers, 16–64 bytes per
instruction. The per-byte loop pays a bounds check on both arrays, two increments, a modulo, and a
loop test *per byte*; `arraycopy` validates the whole range once. (94 GB/s is above DRAM bandwidth —
the 1 MB ring is cache-resident, which is exactly where wide moves win.)

**The concurrency win matters more than the copy speed.** A per-byte writer against a saturated
buffer blocks *per byte*: one park/unpark round trip each, ~1–5 µs of scheduler cost. Pushing 1 MB
that way is ~1,000,000 context switches. Taking the whole free run per pass drops that to
O(bytes / capacity) — a handful.

`capacity - writeIndex` is the entire wrap story: if the chunk fits in it, one `arraycopy`;
otherwise two, the second starting at index 0.

## `notifyAll` vs two conditions — measured, and the answer is "it depends"

The intuition *"`notifyAll` wakes threads that can't proceed, so two conditions must be faster"* is
right in principle and **wrong below real contention.** 512 KB per producer through a 256-byte
buffer in 64-byte chunks (a deliberate worst case — it blocks on nearly every operation):

| P/C | monitor + `notifyAll` | 2 conditions + `signalAll` | 2 conditions + `signal` |
| --- | --------------------- | -------------------------- | ----------------------- |
| 1P/1C |  9 ms |   8 ms |   8 ms |
| 2P/2C | 18 ms |  26 ms |  29 ms |
| 4P/4C | 81 ms | 118 ms | 100 ms |
| 8P/8C | 243 ms | 181 ms | **155 ms** |

(Single-run microbenchmark, meaningful run-to-run variance — read the shape, not the digits.)

- **1P/1C — identical.** One waiter of each kind; nothing to storm.
- **2P/2C and 4P/4C — the intrinsic monitor wins.** `ReentrantLock`/`Condition` allocates an AQS
  node per `await()` and adds an indirection per call; intrinsic monitors have heavily optimised JIT
  fast paths and `notifyAll()` on a two-element wait set is nearly free. Few waiters means you pay
  the machinery and get no herd savings back.
- **8P/8C — conditions win.** `notifyAll()` wakes 15 threads, 14 of which re-acquire the lock
  serially, recheck, and sleep again.

**Crossover is around 8 threads.** Also note `signalAll` per condition is *not* where the win is —
it narrows the herd to one kind but still wakes all of them. The gain is specifically single
`signal()`, which costs you a chaining obligation (`if (cnt > 0) notEmpty.signal()` after a read) and
a lost-wakeup hang if you get it wrong.

The current implementation uses **`Lock` + two conditions + `signalAll`**, which is the right default:
correct without a chaining argument, and it wins where it matters.

### The leftover that silently undid it

`read` kept its `synchronized` keyword after the migration, so it held **two** locks — the intrinsic
monitor *and* the `ReentrantLock`. And `Condition.await()` releases only the `Lock` it came from:

```
reader parked in Condition.await()
  can another thread acquire synchronized(buffer)?  false   <- monitor held across await()
  second reader's thread state:                     BLOCKED <- stuck on a reader, not waiting for data
```

Not a deadlock (writers only need the `Lock`), so the whole suite passed — including the
4-producer/4-consumer test. But **only one reader could be inside `read()` at a time, including while
parked**, which deletes exactly the concurrency the migration bought. 8P/8C: 264 ms with it, 185 ms
without. `BLOCKED` → `WAITING` after removal is the proof.

It was also the P06 deadlock pattern in miniature: two locks in a fixed order with a blocking wait
inside the inner one. Benign only because nothing else touched `this`.

## Condition naming

Name a condition for **the state you're waiting to become true**, not the state that blocked you:
`notEmpty` / `notFull`, matching `ArrayBlockingQueue`. `emptyCondition.signalAll()` immediately after
making the buffer non-empty reads backwards, and that's how inversions hide.

## Partial write on failure is unrecoverable — by design

If `await()` throws `InterruptedException` after three chunks are deposited, the exception propagates
and `written` is lost. The caller cannot tell whether 0 or `len - 1` bytes made it in.

You cannot roll back in general, because a consumer may already hold the earlier bytes. Three
responses:

1. **Rollback when nothing was consumed.** Your bytes are the *tail* of the live region, so
   `if (cnt >= written) cnt -= written;` discards exactly them. Single-writer only — with two
   writers, B's bytes can land after A's during A's `await()`, and A's rollback would eat them.
2. **`awaitUninterruptibly()` and re-assert the interrupt on exit.** The write never leaves the
   stream partial; the interrupt is deferred to the caller once state is consistent. Same pattern as
   `lock()` vs `lockInterruptibly()`. Cost: `write` becomes uncancellable — if the consumer dies the
   writer parks forever with a pending interrupt nobody can act on.
3. **Introduce a transaction boundary at a higher layer** — message framing (whole message or
   nothing), or claim/publish so readers never see unpublished bytes (what the LMAX Disruptor does).

The constraint that makes this a genuine trade-off:

> **Atomic writes require the atomic unit to fit inside the buffer.**

You cannot have both "writes are atomic" and "a single write may exceed capacity". This
implementation deliberately chose the second — `write(100)` into a capacity-4 buffer works, and it's
a test — which *forces* non-atomic writes, because the operation physically cannot complete without
the consumer running partway through it. Java's streams make the same choice: an `IOException`
partway through `OutputStream.write` leaves the stream undefined.

## Known limitations

- **Failed `write` leaves the stream undefined** — see above. Documented, not fixed.
- **`checkBounds` can overflow.** `offset + len - 1 >= arr.length` wraps negative for large values
  (`offset = MAX_VALUE, len = 2` passes a check it should fail). `len > arr.length - offset` cannot.
- **`len == 0`** is checked inside the lock; it touches no shared state and could return before
  acquiring.
- **`size()`** is a snapshot that is stale the instant it returns — fine for tests, not for logic.
- Condition fields are still named `emptyCondition`/`fullCondition`.

## Verified

`Test/BoundedByteBufferTest.java` — plain `main()`-based, no JUnit. 13 cases, 5/5 clean runs.
Every potentially-blocking test runs its threads as daemons with a bounded `join`, so a deadlock or
lost wakeup **fails** the suite rather than freezing it.

The two that earn their keep:

- **512 KB through a 64-byte buffer**, write chunk 999 / read chunk 337 — wraps ~8,000 times and
  blocks nearly continuously. Mismatched, odd chunk sizes are what expose wrap-around and accounting
  bugs; equal-sized chunks hide them completely.
- **4 producers × 4 consumers**, 20,000 bytes each, capacity 97 (not a power of two), chunks 701 and
  311. Byte *order* is undefined across multiple consumers, so it asserts the **multiset** via a
  256-bucket histogram: every byte written comes out exactly once. This is where a
  `notify()`-instead-of-`notifyAll()` regression would surface.

Plus: payload containing `0x00` and `0xFF` (the sentinel regression), `len > capacity`, capacity 1,
empty payload, best-effort read, `close()` releasing a parked reader, drain-then-EOF, write after
close rejected, invalid arguments, and 25 randomised trials over random capacity and chunk sizes with
a fixed seed.

## 30-second recall

> Fixed `byte[]` + `readIndex` + `cnt`. **The array never encodes occupancy** — all 256 byte values
> are legal data, so any in-band sentinel collides (107,104 zeros in `/bin/ls`); `cnt` is the
> widened type, same reason `InputStream.read()` returns `int` not `byte`. `writeIndex` is derived,
> not stored. **`write` is all-or-nothing and loops taking `min(len - written, capacity - cnt)`** —
> waiting for the whole `len` deadlocks when `len > capacity`; **`read` is best-effort**, returning
> what's there or `-1`, because a reader demanding `len` hangs against a finished producer. `close()`
> is the termination signal. **Any `wait()` reachable after mutating state needs a signal in front of
> it** — `signalAll` last in the loop body, `await` first, so the invariant holds for free. `toEnd` is
> an `arraycopy` length only, **never** an accounting quantity: `cnt -= toEnd` instead of `chunk`
> gives you the right byte count with wrong bytes. `arraycopy` over a byte loop is 167x, and cuts
> park/unpark round trips from O(bytes) to O(bytes/capacity). `notifyAll` on one monitor beats two
> conditions below ~8 threads (AQS node per `await` costs more than the wasted wakeups) and loses
> above it; and never leave a `synchronized` on a method that also takes the `Lock` — `await()`
> doesn't release the monitor.
