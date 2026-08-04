# Solution Notes

Last-day revision checklist:

- First clarify whether the system is byte-based or character-based. In Java, `String.length()` counts UTF-16 code units, not raw bytes.
- For interview implementation, it is acceptable to treat ASCII strings as byte-like unless the interviewer asks about encoding.
- Define sparse-write behavior before coding. A simple choice is to fill unwritten gaps with `'\0'`.
- `write(offset, data)` overwrites the range `[offset, offset + data.length())`.
- `read(offset, length)` returns data from `[offset, min(size, offset + length))`.
- Validate `offset >= 0`, `length >= 0`, and `data != null`.
- Watch for integer overflow in `offset + length` and `offset + data.length()`.

## Part 1: Basic Storage

Use a growable buffer:

```java
StringBuilder storage
```

For writes:

1. Validate input.
2. Compute `end = offset + data.length()`.
3. Grow storage until it has length `end`.
4. Overwrite characters from `offset` to `end - 1`.

For reads:

1. Validate input.
2. If `offset >= storage.length()`, return `""`.
3. Compute `end = Math.min(storage.length(), offset + length)`.
4. Return `storage.substring(offset, end)`.

This gives a clean single-threaded baseline.

## Part 2: Concurrent Storage

Start with correctness, then improve concurrency.

### Simple Correct Version

Use one lock around the whole storage:

```java
private final Object lock = new Object();
```

or:

```java
private final ReentrantLock lock = new ReentrantLock();
```

This is easy to implement and safe, but all operations block each other.

### Better Read-Heavy Version

Use:

```java
ReadWriteLock rwLock = new ReentrantReadWriteLock();
```

- `read()` takes the read lock.
- `write()` takes the write lock.

This allows multiple concurrent readers but still serializes every write globally.

### Higher-Concurrency Version

Use segmented locking.

Idea:

- Split the file into fixed-size chunks.
- Each chunk has a lock.
- An operation locks only the chunks touched by its offset range.
- Always acquire locks in increasing segment index order.
- Always release locks in reverse order.

This allows non-overlapping reads/writes to proceed independently when they touch different segments.

### Segment Size And Lock Striping

Segment size is a performance knob, not a correctness requirement.

- Smaller segments reduce false contention but increase lock overhead for large operations.
- Larger segments reduce lock overhead but cause more unrelated operations to block each other.
- A practical default can be `4 KB` or `64 KB`, then tune based on workload.
- If operations are usually small/random, prefer smaller segments.
- If operations are usually large/sequential, prefer larger segments.

Do not map locks by dividing the current file size into `N` ranges. That mapping changes when the file grows.

Use a stable mapping:

```java
segmentId = offset / SEGMENT_SIZE;
```

If you want bounded lock memory, use fixed lock striping:

```java
lockIndex = segmentId % NUM_LOCKS;
```

This works even when the file grows beyond `SEGMENT_SIZE * NUM_LOCKS`; lock indexes wrap around. It stays correct because the mapping is deterministic, but it creates false contention when far-apart segments share the same lock.

For operations touching multiple segments:

1. Compute touched segment ids.
2. Map them to lock indexes.
3. Deduplicate lock indexes.
4. Sort them.
5. Acquire locks in increasing order.
6. Release locks in reverse order.

This sorted acquisition rule avoids deadlock.

## Interview Strategy

A strong answer can be staged:

1. Implement the single-threaded version first.
2. Make it correct with one global lock.
3. Discuss read-write lock as an improvement for read-heavy workloads.
4. Discuss segmented locks for non-overlapping range concurrency.

Do not jump into segmented locking before the basic semantics are clean. The interviewer is usually looking for correctness first, then concurrency reasoning.

## Important Invariants

- Storage length only grows while holding the write lock or relevant segment locks.
- A read must not inspect storage while another thread is resizing or mutating the same range.
- Overlapping writes must not interleave character-by-character in an unsafe way.
- Segment locks must be acquired in sorted order to avoid deadlock.
- If metadata like file size is shared across segments, protect it with a global metadata lock or make the segment design account for it.

## Testing Checklist

- Write at offset `0`, then read exact range.
- Write in the middle and verify overwrite.
- Write past end and verify sparse gap behavior.
- Read past end and verify truncation.
- Read from offset beyond end returns empty string.
- Reject negative offset.
- Reject negative length.
- Reject null data.
- Concurrent non-overlapping writes.
- Concurrent overlapping writes.
- Concurrent reads during writes.
- Stress test with many small random reads/writes.
