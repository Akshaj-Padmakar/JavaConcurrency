# Offset File Storage

Implement an in-memory file-like storage system that supports offset-based reads and writes.

This problem has two parts.

## Part 1: File Read and Write With Offset

Design a class that mimics basic file storage.

The storage should support:

```java
void write(int offset, String data)
String read(int offset, int length)
```

### Write

```java
write(offset, data)
```

Writes `data` into the storage starting at byte offset `offset`.

Rules:

- `offset` is zero-based.
- `offset` is non-negative.
- If the write starts beyond the current end of storage, the storage should grow.
- If the write overlaps existing data, overwrite the existing bytes in that range.
- If the write extends past the current end, append/grow as needed.

### Read

```java
read(offset, length)
```

Reads up to `length` bytes starting from byte offset `offset`.

Rules:

- `offset` is zero-based.
- `offset` and `length` are non-negative.
- If `offset` is beyond the current end of storage, return an empty string.
- If `offset + length` goes past the current end, return only the available bytes.

### Example

```text
write(0, "EngineBogie")
read(6, 5)
```

Output:

```text
"Bogie"
```

### More Examples

```text
write(0, "abcdef")
write(2, "XYZ")
read(0, 6)
```

Output:

```text
"abXYZf"
```

```text
write(5, "abc")
read(0, 8)
```

The expected behavior for unwritten gaps should be defined by you. Common choices:

- Fill gaps with `'\0'`.
- Fill gaps with spaces.
- Treat gaps as empty/uninitialized and document the behavior.

For interview clarity, prefer choosing one behavior explicitly.

## Part 2: Concurrent File Access With Offsets

Extend the storage system to support concurrent reads and writes.

The system should remain correct when multiple threads call:

```java
write(offset, data)
read(offset, length)
```

at the same time.

### Correctness Requirements

- Reads should never observe corrupted internal state.
- Writes to overlapping ranges must be synchronized so data integrity is preserved.
- Writes to the same offset must have a deterministic happens-before order based on lock acquisition.
- Reads overlapping an active write should either wait or observe a fully completed write, not a partial unsafe mutation.
- The storage should grow safely when writes extend beyond the current size.

### Performance Goal

Avoid blocking unrelated operations unnecessarily.

Examples:

- Two reads should be able to run concurrently.
- A read from range `[0, 10)` should not necessarily block a write to range `[1000, 1010)`.
- Two writes to non-overlapping ranges should not necessarily block each other.
- Operations touching overlapping ranges must coordinate.

## Synchronization Approaches To Discuss

You may implement or discuss one of these approaches.

### Approach 1: Single Mutex

Use one lock around all reads and writes.

Pros:

- Simple.
- Easy to reason about.
- Correct.

Cons:

- Poor concurrency.
- Non-overlapping operations block each other.

### Approach 2: Read-Write Lock

Use one `ReadWriteLock` for the whole storage.

Pros:

- Multiple readers can proceed together.
- Simpler than range locking.

Cons:

- Any write blocks all reads and writes, even non-overlapping ones.

### Approach 3: Segmented Locking

Divide the storage into fixed-size segments. Each segment has its own lock.

Pros:

- Non-overlapping operations on different segments can proceed concurrently.
- More scalable than a single global lock.

Cons:

- More complex.
- Operations spanning multiple segments must acquire multiple locks.
- Locks must be acquired in a consistent order to avoid deadlock.

## Requirements

- Validate invalid inputs.
- Define behavior for sparse writes.
- Define whether storage is byte-based or character-based.
- Keep read/write semantics clear and deterministic.
- In the concurrent version, explain why your locking strategy is correct.
- If using segmented locks, always acquire segment locks in increasing segment order.

## Follow-Up Questions

- How would this change if storage were backed by disk instead of memory?
- How would you support very large files?
- How would you implement append?
- How would you support truncation?
- How would you make writes atomic across multiple ranges?
- How would you test overlapping reads and writes?
