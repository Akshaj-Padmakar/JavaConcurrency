# Concurrent File Copy

> Interview form (e.g. Rubrik): _"Concurrent File Data Transfer"_ — copy `src` → `dst` with low-level
> positional (offset-based) I/O, first single-threaded, then multi-threaded.

## Problem

Copy the contents of a source file to a destination file using **low-level file descriptors** and
**positional** reads/writes. The file may be **too large to fit in memory**, so you must stream it in
**fixed-size chunks** through a buffer.

### Provided primitives (positional / stateless — no shared "current offset")

```
int  open(String name)                              // returns fd, or throws
int  pread(int fd, byte[] buf, long offset)         // reads up to buf.length bytes AT offset; returns #read (0 = EOF)
void pwrite(int fd, byte[] buf, int len, long offset)// writes len bytes AT offset
void close(int fd)
```

`pread`/`pwrite` take an explicit `offset` and **do not** advance a per-fd cursor. That's the key
property that makes the multithreaded version possible: independent threads can read/write different
regions concurrently without stepping on a shared position.

### Signature

```java
void copy(String dst, String src) throws IOException;
```

## Part 1: Single-threaded (get this rock-solid first)

Stream in chunks; the loop is the whole problem:

```
offset = 0
loop:
  n = pread(srcFd, buf, offset)      // n may be < buf.length (short read) — that's normal
  if (n == 0) break                  // EOF
  pwrite(dstFd, buf, n, offset)      // write exactly n bytes (NOT buf.length)
  offset += n
```

### The correctness traps

- **Write exactly `n`, not `buf.length`.** The last chunk (and any short read) returns fewer bytes;
  writing the full buffer would append garbage/stale bytes.
- **Short reads are legal.** `pread` returning `n < buf.length` is not EOF and not an error — only
  `n == 0` (or the platform's EOF signal) means done.
- **Resource safety.** Close **both** fds even when a read/write throws. Close the destination even if
  opening it succeeded but the copy failed. Don't leak fds. (Java: try-with-resources or nested
  `finally`; and a failed `close` on the *destination* can itself signal data loss — surface it.)
- **Open ordering.** Open src (read) first; if opening dst fails, close src before returning.

## Part 2: Multi-threaded (the Rubrik ask)

Because `pread`/`pwrite` are **positional**, you can split the file into **non-overlapping ranges**
and copy each range on its own thread — no shared cursor, so no locking on the file itself.

```
ranges = split(fileSize, CHUNK)          // [0,CHUNK), [CHUNK,2*CHUNK), ...  (last is partial)
for each range r: submit task -> copyRange(srcFd, dstFd, r.offset, r.length)
wait for all; if any failed, propagate the first error
```

### What makes it correct (and what to watch)

- **Disjoint ranges ⇒ no data race on the file.** Two threads never touch the same bytes, and
  positional I/O means no shared offset to synchronize. This is *embarrassingly parallel* precisely
  because the API is stateless.
- **Bounded parallelism.** Don't spawn one thread per chunk for a 100 GB file — use a fixed
  `ExecutorService` (thread pool sized to I/O concurrency, often small; disk is the bottleneck).
- **Error aggregation.** If any range fails, the whole copy must fail; capture the first exception
  (e.g. via `Future.get()`), cancel the rest, and still **close both fds** and ideally clean up the
  partial `dst`.
- **All-or-nothing / atomicity.** A crash mid-copy leaves a partial `dst`. Real tools copy to a temp
  file and `rename` on success (atomic on POSIX). Worth mentioning.
- **Does parallelism even help?** For a single spinning disk, sequential is often faster (seeks hurt);
  parallel wins on SSD/NVMe, networked/striped storage, or when read and write are on different
  devices. Know when to *not* parallelize.

## Points to Ponder / follow-ups

- **Why is positional I/O the enabling detail?** Contrast with a stateful `read`/`write` that advances
  a shared per-fd cursor — that would force serialization or per-thread fds.
- **Buffer size?** Too small = syscall overhead; too large = memory + poor overlap. Typical 64 KB–1 MB;
  align to block size. One buffer **per thread** (never share a mutable buffer across threads).
- **Read/write overlap (pipeline).** Instead of range-splitting, a **producer/consumer**: reader
  thread(s) fill buffers into a bounded `BlockingQueue`, writer thread(s) drain them. Preserves order
  via sequence numbers. When is pipelining better than range-splitting?
- **Backpressure.** If the writer is slower than the reader, the bounded queue blocks the reader —
  that's the point (don't OOM buffering the whole file).
- **Partial-write handling.** If `pwrite` can write fewer than `len` bytes, loop until all `len` are
  written (like `pread`'s short read, mirrored).
- **Error/interrupt handling.** Interrupt propagation, cancelling in-flight tasks, cleaning the
  partial destination.
- **Verification.** How would you test correctness? (Byte-for-byte compare, checksums, random sizes
  incl. empty file, size not a multiple of chunk, size exactly one chunk.)
- **Idempotency / resume.** Could you resume a partially-copied file? (Positional I/O makes
  range-level resume natural.)

## Requirements

- Correct for **any** size: empty, smaller than a chunk, exact multiple of chunk, huge.
- Stream in fixed chunks — never load the whole file.
- **Write exactly the bytes read**; handle short reads.
- **No fd leaks**, even on error; propagate the first failure.
- Multi-threaded version: disjoint ranges, bounded pool, aggregated errors.
