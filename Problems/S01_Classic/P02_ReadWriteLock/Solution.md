# Read-Write Lock — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §4.2 — **Readers–Writers**.
> This repo's `ReadWriteLock.java` is **monitor-based, writer-preference, reentrant**.

## One-line idea

Many readers **or** one writer — never both. Guard shared state with a mutex; block the side that
can't safely proceed. Policy here = **writer-preference** (a waiting writer blocks _new_ readers).

**Why writer-preference (our choice):** delaying a writer means readers keep seeing stale data
anyway, so we'd rather let the writer through and drain in-flight readers first. Trade-off: a steady
stream of writers can **starve readers**. Accept and state this.

## State model (this implementation)

| Field                               | Meaning                                              |
| ----------------------------------- | ---------------------------------------------------- |
| `Map<Thread,Integer> readingThread` | per-thread read hold count (enables reentrant reads) |
| `writer`, `writeCnt`                | current writer + its reentrant depth                 |
| `writingRequest`                    | # writers waiting → drives writer-preference         |

Grant rules:

- **Read granted** if: you're the current writer (downgrade), OR already reading (reentrant), OR no
  writer and no writer waiting.
- **Write granted** if: you're the current writer (reentrant), OR zero readers, OR you're the
  **sole** reader (upgrade).

## Things to keep in mind (checklist)

- ✅ **`while`, not `if`** around every `wait()` (spurious wakeups + re-check after reacquiring).
- ✅ **`notifyAll()`, never `notify()`** — readers and writers wait on the **same monitor** for
  **different** predicates. `notify()` could wake a thread whose predicate is still false while the
  one that could proceed keeps sleeping → lost wakeup / deadlock.
- ✅ **Reentrant-read check must come BEFORE the writer-preference check.** A reader re-entering
  while a writer waits must be let through, else it deadlocks against the writer that's waiting on
  it. (Order in `grantReadAccess` is correct — keep it.)
- ✅ Unlock from a non-holder → `IllegalMonitorStateException`.
- ✅ All shared state under `synchronized(this)` → no visibility races.

## Known bugs & limitations

### 🔴 BUG — `writingRequest` leaks on interrupt (must fix)

If `wait()` in `writeLock()` throws `InterruptedException`, `writingRequest--` never runs, so it
stays ≥ 1 forever → **every future reader is denied indefinitely** (`grantReadAccess` sees
`writingRequest > 0`). One interrupted writer poisons the whole lock. Fix = decrement in `finally`:

```java
writingRequest++;
try {
    while (!grantWriteAccess(thread)) this.wait();
} finally {
    writingRequest--;      // runs on normal grant AND on interrupt
}
writer = thread;
writeCnt++;
```

(`readLock()` has no such leak — an interrupt there leaves no half-updated state.)

### 🟡 LIMITATION — lock upgrade can deadlock

Upgrade is allowed only for the **sole** reader (`size()==1`). But nothing stops **two** readers
from both calling `writeLock()`: both see `size()==2` → both denied → both `wait()` → neither can
`readUnlock()` (both blocked in `writeLock`) → **silent deadlock**. This is why the JDK's
`ReentrantReadWriteLock` **forbids upgrade entirely**. Our version is "best-effort upgrade": it
_blocks_ on the unsafe case instead of _failing fast_. Safer design would be a non-blocking
`tryUpgrade()` that returns `false` when not the sole reader.

### 🟡 LIMITATION — upgrade leaves you holding BOTH locks

After a sole-reader upgrade we set `writer=thread` but **don't** remove it from `readingThread`. So
the caller now holds read **and** write and must release **both**. Forgetting `readUnlock()` leaks a
phantom reader that blocks future writers forever.

## Upgrade vs Downgrade (memorize this)

| Direction                              | Safe?  | Why                                                                                                                                      |
| -------------------------------------- | ------ | ---------------------------------------------------------------------------------------------------------------------------------------- |
| **Downgrade** W→R                      | ✅ yes | You start exclusive → no one to deadlock against. Acquire read while holding write, then drop write — no gap.                            |
| **Upgrade** R→W (atomic, blocking)     | ❌ no  | Two readers upgrading each wait for the other to drop read → deadlock. **Logically impossible** to make safe + blocking + atomic.        |
| **Upgrade** via release-then-reacquire | ⚠️ ok  | Drop read, take write, **re-check state** (a writer may have slipped into the gap). Non-atomic.                                          |
| **Upgrade** via `tryConvert`           | ✅ ok  | Non-blocking: succeeds atomically (e.g. sole reader) or fails → you fall back to release-reacquire. `StampedLock.tryConvertToWriteLock`. |

**Mental model:** a reader stays a reader until it fully unlocks; only then can it become a writer.
There is **no safe blocking atomic upgrade**.

Downgrade pattern (the safe, atomic one):

```java
writeLock();
try {
    ...write...
    readLock();          // acquire read while still holding write — always succeeds
} finally {
    writeUnlock();       // now hold only read; no writer could have interleaved
}
...read...
readUnlock();
```

## JDK equivalents

- **`ReentrantReadWriteLock(fair?)`** — reentrant, optional fairness, supports **downgrade**,
  **forbids upgrade** (deadlocks). The standard answer.
- **`StampedLock`** (Java 8+) — adds **optimistic reads**: read without locking, then
  `validate(stamp)`; retry if a writer intervened. Also `tryConvertToWriteLock`. Not reentrant.
  Big throughput win when writes are rare.

---

## Fair / No-Starve version (neither side starves)

Writer-preference starves readers; reader-preference starves writers. A **turnstile** fixes both:
every thread passes a FIFO gate first, and a waiting writer _holds_ the gate so new readers pile up
_behind_ it (letting in-flight readers drain), then the writer runs. This is the book's no-starve
solution, in Java with semaphores (use **fair** semaphores for FIFO ordering).

```java
import java.util.concurrent.Semaphore;

/** No-starve readers-writers. Not reentrant, no upgrade — teaching/fairness reference. */
public class FairReadWriteLock {
    private int readers = 0;
    private final Semaphore mutex     = new Semaphore(1, true); // guards `readers`
    private final Semaphore roomEmpty = new Semaphore(1, true); // held while a writer OR first reader is inside
    private final Semaphore turnstile = new Semaphore(1, true); // FIFO gate; a waiting writer holds it

    public void readLock() throws InterruptedException {
        turnstile.acquire();          // if a writer is waiting, it holds this → new readers queue here
        turnstile.release();          // otherwise pass straight through

        mutex.acquire();
        try {
            readers++;
            if (readers == 1) roomEmpty.acquire();   // first reader locks out writers
        } finally {
            mutex.release();
        }
        // ---- read happens between readLock() and readUnlock() ----
    }

    public void readUnlock() throws InterruptedException {
        mutex.acquire();
        try {
            readers--;
            if (readers == 0) roomEmpty.release();    // last reader lets a writer in
        } finally {
            mutex.release();
        }
    }

    public void writeLock() throws InterruptedException {
        turnstile.acquire();          // block NEW readers behind me (no reader flood)
        roomEmpty.acquire();          // wait for in-flight readers / current writer to finish
        // ---- write happens between writeLock() and writeUnlock() ----
    }

    public void writeUnlock() {
        turnstile.release();          // let the next queued thread (reader or writer) proceed
        roomEmpty.release();
    }
}
```

**Why it's fair (no starvation):**

- **Writers don't starve:** a writer grabs the `turnstile`, so no _new_ reader can enter. Existing
  readers finish, `readers` hits 0, `roomEmpty` frees, the writer runs.
- **Readers don't starve:** the writer releases the `turnstile` on exit, so the next arrivals
  (readers or the next writer) proceed in arrival order. Readers still batch (many pass the
  turnstile and share `roomEmpty` via the counter).
- Two writers serialize naturally on the `turnstile` (second blocks until first releases).

**Caveats of this version:** not reentrant, no upgrade/downgrade, and `turnstile.acquire()/release()`
in `readLock` can leak the permit if interrupted between the two calls (guard with try/finally in
production). Strict FIFO across the whole system really wants an AQS-based lock — this gives
_bounded waiting_ (no-starve), which is what "fair" usually means in interviews.

## 30-second recall

> Many readers XOR one writer. Writer-preference here: waiting writer blocks new readers.
> `while`-guard waits; `notifyAll` (shared monitor, different predicates); reentrant-read check
> before writer-preference check. **No safe blocking atomic upgrade** — reader→writer needs
> release-then-reacquire (+recheck) or `tryConvert`. Downgrade (W→R) is safe. Fair = **turnstile**.
