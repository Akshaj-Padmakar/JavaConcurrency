# Blocking Queue

## Problem

Design a generic, thread-safe **bounded blocking queue** that supports multiple producers and multiple consumers.

The queue has a fixed capacity:

- Producers must block when the queue is full until space becomes available.
- Consumers must block when the queue is empty until an element is available.

In addition to blocking operations, the queue should also support:

- Non-blocking insertion/removal.
- Timed insertion/removal.
- Peek, size, and remaining capacity queries.

## Requirements

Implement the following operations:

### Insertion

- `add(T)` – Insert immediately; throw if the queue is full.
- `offer(T)` – Insert immediately; return `false` if full.
- `put(T)` – Block until space is available.
- `offer(T, timeout, unit)` – Wait up to the timeout for space.

### Removal

- `poll()` – Remove immediately; return `null` if empty.
- `take()` – Block until an element is available.
- `poll(timeout, unit)` – Wait up to the timeout for an element.

### Inspection

- `peek()` – Return the front element without removing it.
- `size()`
- `remainingCapacity()`

## Constraints

- The queue must be thread-safe.
- Support multiple concurrent producers and consumers.
- Preserve FIFO ordering.
- Avoid busy waiting; use proper synchronization primitives.
