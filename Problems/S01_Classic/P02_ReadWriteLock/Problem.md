# Read-Write Lock

> A structure in memory gets read constantly and written rarely. A plain mutex works, but it makes
> a thousand readers queue up behind each other for no reason — they don't conflict. Build me a lock
> with two modes: a read mode that any number of threads can hold at once, and a write mode that's
> exclusive. Make it reentrant. And tell me who starves.

## What you're building

The lock that sits in front of a read-heavy shared structure — a snapshot metadata index, a routing
table, a config cache. Something looked up thousands of times a second and updated once a minute
when a backup completes.

The insight is that **readers don't conflict with each other.** Two threads reading the same map
cannot corrupt it or observe each other's half-finished work; there is no half-finished work. Only a
writer creates a moment where the structure is inconsistent. So the only pairs that must be kept
apart are reader↔writer and writer↔writer. A mutex is a blunt instrument that also keeps apart
reader↔reader, and that's pure waste when reads dominate.

The cost is bookkeeping: you now have to track how many readers are inside, who they are, and who's
waiting. That bookkeeping is itself a critical section guarded by a real mutex — so this lock is
built *on top of* one, not instead of one.

## Worked example

Four threads. Requests arrive in the order shown.

| # | Thread | Call | State after | Result |
|---|---|---|---|---|
| 1 | R1 | `readLock()` | readers `{R1}` | granted — no writer |
| 2 | R2 | `readLock()` | readers `{R1, R2}` | granted — readers share |
| 3 | W1 | `writeLock()` | readers `{R1, R2}`, writers waiting = 1 | **blocks** — readers present |
| 4 | R3 | `readLock()` | unchanged, R3 waiting | **blocks** — a writer is already waiting |
| 5 | R1 | `readUnlock()` | readers `{R2}` | returns |
| 6 | R2 | `readUnlock()` | readers `{}` | returns; W1 becomes runnable |
| 7 | W1 | *(resumes)* | writer = W1 | granted — exclusive |
| 8 | W1 | `writeUnlock()` | writer = none | R3 granted |

**Step 4 is the whole design.** R3 is blocked even though only readers hold the lock and R3 would
not conflict with them. Letting R3 in would be harmless once — but readers keep arriving, the reader
count never reaches zero, and W1 waits forever.

## The API

```java
public class ReadWriteLock {
    public void readLock()  throws InterruptedException;
    public void readUnlock();
    public void writeLock() throws InterruptedException;
    public void writeUnlock();
}
```

Four methods, no state exposed. Unlocking is the caller's obligation, which is why every call site
looks like this:

```java
ReadWriteLock rw = new ReadWriteLock();

rw.readLock();
try { return index.get(key); } finally { rw.readUnlock(); }

rw.writeLock();
try { index.put(key, value); } finally { rw.writeUnlock(); }
```

Note what the API does *not* have: no `tryLock`, no timeouts, no way to ask who holds it. Adding
those is a follow-up, not the problem.

## Constraints

- **No `ReentrantReadWriteLock`, no `StampedLock`.** Those *are* the answer to this question.
- `ReentrantLock` + `Condition` are allowed — they're the primitive you build *from*. Be ready to
  say you can drop to `synchronized` / `wait` / `notifyAll`, and to do it if asked.
- Plain `java.util` for the bookkeeping (a `HashMap` of thread → count is fine) — the container
  isn't the subject.
- **No busy-waiting**, and no `volatile`-flag tricks. All state is read and written under the lock.

## Requirements

- **The invariant, at every instant:** many readers and no writer, *or* one writer and no readers.
  Never both, never two writers.
- **Writer preference:** once a writer is waiting, newly arriving readers block. Readers already
  inside are allowed to finish.
- **Reentrant reads:** a thread holding the read lock may take it again.
- **Reentrant writes:** a thread holding the write lock may take it again, and releases it only when
  its acquisitions balance out.
- **Downgrade:** a thread holding the write lock may take the read lock.
- **Upgrade:** a thread holding the read lock may take the write lock **only if it is the sole
  reader**.
- Unlocking a lock you don't hold throws `IllegalMonitorStateException`.
- Blocked threads respond to interruption; no CPU is consumed while waiting.

## Edge cases

- `readUnlock()` / `writeUnlock()` called by a thread that never locked.
- `readUnlock()` called more times than `readLock()`.
- A thread that already holds the read lock calls `readLock()` again **while a writer is waiting**.
- Two readers attempt to upgrade at the same moment.
- A writer holding the lock calls `readLock()`, then `readUnlock()`, then `writeUnlock()`.
- The last reader leaves while both a writer and several readers are queued.
- A thread interrupted while blocked in `readLock()` or `writeLock()`.

## Questions to answer before you code

1. Write the invariant as a boolean expression over your actual fields. What are the fields? Is a
   single `int readerCount` enough to express it?
2. Why must the read count be tracked **per thread** rather than as one integer? Name the specific
   operation that becomes impossible with one counter.
3. A thread already holding the read lock calls `readLock()` again while a writer waits. Grant or
   block? Trace both choices to their conclusion — one of them ends badly.
4. Two readers each decide to upgrade to the write lock at the same instant. Walk it through. What
   is this failure called, and can any policy prevent it?
5. Downgrade is safe, upgrade is not. What exactly is asymmetric between them?
6. Readers and writers are waiting for **different** conditions. How many wait-sets, and for each
   one, `signal` or `signalAll`? Justify each independently.
7. When a writer releases, who do you wake — the next writer, all readers, or both? Does the order
   of those two wakeups change anything?
8. Your "a writer is waiting" counter is incremented before the wait. Where must the decrement live,
   and what breaks if it's on the normal path instead?
9. Writer preference starves readers; reader preference starves writers. Which starvation is
   acceptable for a read-heavy cache, and what would you change to get FIFO fairness instead?
10. Under what read/write mix and critical-section length is this lock **slower** than a plain
    mutex? What does that imply about when to reach for it?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "many can read, one can write" | shared vs exclusive mode — the readers–writers problem |
| "a waiting writer blocks new readers" | writer preference |
| "readers keep arriving so the writer never gets in" | writer starvation |
| "a thread can take a lock it already holds" | reentrancy |
| "write lock, then read lock, still holding both" | lock downgrade |
| "read lock, then write lock" | lock upgrade |
| "each waits for the other to let go" | deadlock — specifically upgrade deadlock |
| "the place a thread waits for one specific thing" | condition variable, wait-set |
| "re-check the situation after waking" | guarded suspension, predicate loop |
| "read without locking, then check nothing changed" | optimistic read (`StampedLock`) |
| "who gets in next" | acquisition policy / fairness |
| "unlocking something you never locked" | `IllegalMonitorStateException` |
