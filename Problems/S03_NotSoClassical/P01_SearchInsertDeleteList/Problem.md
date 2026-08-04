# Search-Insert-Delete

> Book ref: _The Little Book of Semaphores_ §6.1.
> Common interview form (e.g. Rubrik): _"3-state access control"_ / _"synchronized singly-linked
> list with searchers, inserters, deleters."_

## Problem

A singly-linked list is accessed by **three kinds of threads**. Design the synchronization so the
following concurrency rules hold:

- **Searchers** — only read the list. **Any number run concurrently** with each other.
- **Inserters** — append to the tail. **Mutually exclusive with other inserters**, but may run
  **concurrently with searchers**.
- **Deleters** — remove a node from anywhere. **Exclusive with everything** — no other deleter,
  inserter, or searcher may be active during a delete.

### API

```java
boolean search(E key);
void    insert(E value);
boolean delete(E key);
```

### Compatibility matrix (the spec, in one table)

|            | Searcher | Inserter | Deleter |
| ---------- | :------: | :------: | :-----: |
| **Searcher** |   ✅    |    ✅    |   ❌    |
| **Inserter** |   ✅    |    ❌    |   ❌    |
| **Deleter**  |   ❌    |    ❌    |   ❌    |

Read it as: *many searchers + at most one inserter can overlap; a deleter runs completely alone.*

## Why it's not just a read-write lock

It looks like readers-writers, but there are **three** roles, not two, and the middle one (insert)
is special: it's a "writer" that **tolerates readers** (searchers) but not other writers. So you need
per-role state, not a single reader-count.

## The two things being tested

1. **Scheduling** — encode the matrix with the right counters/flags and signal the right waiters.
2. **Memory visibility (the deep part)** — because the point is to let search + insert **overlap**,
   the actual list read/write must happen **outside** any exclusive lock. That means the shared node
   fields are touched by two threads with no common lock held → you must **publish** them
   (`volatile`/`final`) or you have a data race. Scheduling ≠ visibility.

## Points to Ponder / interview follow-ups

- **Which primitives?** Monitor (`ReentrantLock` + 3 `Condition`s + role counters/flags), or
  semaphores (`Semaphore` mutexes + a searcher-count lightswitch). Trade-offs?
- **Starvation of deleters** (asked verbatim in the Rubrik variant): a stream of searchers/inserters
  can starve a deleter forever. How do you prevent it? → **deleter-preference**: once a deleter is
  waiting, block *new* searchers and inserters (`waitingDeleters > 0`), let the in-flight ones drain,
  then run the deleter. (This is the turnstile idea from readers-writers.)
- **The flip side:** deleter-preference can now **starve searchers/inserters** under a delete flood.
  How would you make it **fair** (bounded waiting for everyone)? → a FIFO turnstile / ticket lock.
- **Visibility:** if search and insert truly overlap, is the list access memory-safe? What makes
  `head`, `Node.next`, `Node.val` safe for a searcher to read mid-insert? (`volatile` / `final`.)
- **Why not just hold one exclusive lock for each op?** Because that serializes search+insert and
  throws away the concurrency the problem demands. The whole difficulty comes from *allowing* the
  overlap.
- **Insert at tail vs anywhere:** appending at the tail is *structurally* safe for a concurrent
  searcher (it sees the new node or not — both fine). Would inserting in the *middle* still be safe
  concurrently with searchers? (Harder — pointer rewiring a searcher may be mid-traversal of.)
- **Delete semantics:** delete is fully exclusive here, so it can rewire freely. Could you relax it
  to allow concurrent searchers with careful hazard pointers / RCU? (Lock-free territory — mention
  `ConcurrentLinkedQueue`, `ConcurrentSkipListSet`.)
- **JDK shortcut:** what does `ReentrantReadWriteLock` give you, and why is it **not** enough here?
  (It has only two states; insert-tolerates-search is a third state it can't express.)
- **Fairness param, reentrancy, interruptibility** — does your lock need any of these?
- **Termination / testing:** how do you test that the matrix actually holds (no illegal overlap)
  under stress? (Instrument active counts; assert invariants like "deleterActive ⇒ everything else 0".)

## Requirements

- Enforce the compatibility matrix exactly (no illegal overlap).
- Pick and **state** a starvation policy for deleters.
- Make the concurrent search+insert **memory-safe** (publish shared fields).
- Avoid busy-waiting; use `while`-guarded conditions.
