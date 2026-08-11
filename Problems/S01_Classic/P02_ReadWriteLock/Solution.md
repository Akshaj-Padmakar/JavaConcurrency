# Read-Write Lock — Solution

> Book ref: *The Little Book of Semaphores* §4.2 — Readers–Writers.
> This implementation: **`ReentrantLock` + two conditions, writer-preference, reentrant, upgrade
> allowed for the sole reader.**

## 1. The idea

Many readers **or** one writer, never both. A mutex guards the *bookkeeping*; the two condition
variables park whichever side can't safely proceed. Policy is **writer-preference**: once a writer
is waiting, new readers queue behind it so the reader count can actually reach zero.

## 2. State model

| Field | Meaning |
|---|---|
| `Map<Thread,Integer> readingThread` | per-thread read hold count — identity, not just a count |
| `writer` / `writerReentranceCnt` | current writer and its reentrant depth |
| `writeWaitCnt` | writers waiting — this is what implements writer-preference |

**Grant rules**

- **Read** if: you're the writer (downgrade) · OR you're already reading (reentrant) · OR no writer
  and none waiting.
- **Write** if: you're the writer (reentrant) · OR zero readers · OR you're the **sole** reader (upgrade).

**The invariant that makes it all work**, and which is nowhere in the code:

> While `writer != null`, `readingThread ⊆ {writer}`.

Foreign readers can't enter while a writer holds, and a writer only enters when readers are empty or
the sole reader *is* him. Several things below are correct only because of this.

## 3. Mechanism, and the traps

- **Reentrant-read check must precede the writer-preference gate.** A reader re-entering while a
  writer waits *must* be let in — otherwise it waits for the writer, who waits for its count to hit
  zero. Swap those two lines in `grantReadAccess` and you get a self-deadlock.
- **`signalAll` on `writeWaitCondition`, not `signal`.** The tell is that `grantWriteAccess` takes a
  `Thread` — the sole-reader upgrade means **each waiting writer has a different predicate**. A
  predicate that reads the asking thread can never be satisfied by waking an arbitrary waiter.
- **Every write to `writer`, `writeWaitCnt`, or `readingThread` is a predicate change and owes a
  signal.** Including the decrement on the *interrupt* path. This is the rule; all three bugs in §9
  were instances of it.
- **`writeWaitCnt--` in a `finally`, and it must signal.** The `finally` stops the leak; the signal
  stops the readers who parked *because* the counter was non-zero from sleeping forever.
- **`signal()` in `writeUnlock` is safe — by accident.** Given the §2 invariant, at that moment every
  waiter evaluates `grantWriteAccess` to the same answer, so one wakeup suffices. Relax
  `grantWriteAccess` later and it breaks silently. Prefer `signalAll` unless you write the invariant
  down next to it.

## 4. What to ask the interviewer

1. "`j.u.c.` locks, or build it from `synchronized`/`wait`/`notify`?"
2. "Which starves — do you want writer-preference, reader-preference, or genuinely fair?"
3. "Does it need to be reentrant? That changes the state from a counter to a per-thread map."
4. "Do you want upgrade and downgrade, or is downgrade enough?"
5. "Read/write ratio, and roughly how long is a read? It decides whether this beats a plain mutex."

## 5. Answers to Problem.md §7

1. **Invariant:** at most one `writer`, and `writer != null ⟹ readingThread ⊆ {writer}`. A single
   `int readerCount` is **not** enough — see 2.
2. **Per-thread counts** are needed for two things a bare count can't express: telling "*this* thread
   already reads" (reentrancy) from "*someone* reads", and testing "the sole reader is me" (upgrade).
   Both need identity.
3. **Grant it.** Blocking deadlocks: the re-entrant reader waits on the writer, the writer waits for
   the reader count to drop.
4. **Both wait forever** — each sees `size()==2`, neither can `readUnlock()` because both are parked
   inside `writeLock()`. No policy fixes it; see §8.
5. **Downgrade starts from exclusive** — nobody else holds anything, so there is nothing to wait for
   and no gap for anyone to slip into. **Upgrade starts from shared** — you must wait for holders who
   may be waiting for you.
6. **Two wait-sets.** `readWaitCondition` → `signalAll` (genuine broadcast; all blocked readers become
   grantable at once). `writeWaitCondition` → `signalAll` as well, because waiters have *individual*
   predicates thanks to upgrade. The count of waiters is not what decides it — the shape of the
   predicate is.
7. **Both**, and the order doesn't matter: both signals happen under the lock, so nobody runs until
   you unlock.
8. **In a `finally` — and it must signal `readWaitCondition` when it reaches zero.** The `finally`
   alone fixes the leak but leaves readers parked on a free lock.
9. **Reader starvation is the acceptable one here.** Writers are rare in a read-heavy cache, so the
   pause is bounded. FIFO fairness needs a turnstile (§8).
10. **When critical sections are short.** A map lookup, two counters and a signal cost more than the
    work being protected. RW locks win when reads are *long* and contention is *high* — microseconds
    of work, not nanoseconds. Below that, a plain mutex is faster and much simpler.

## 6. What the interviewer is checking

| Signal | What it proves |
|---|---|
| Asking who starves before coding | You know all three policies exist and are all correct |
| Per-thread map, not an `int` | You thought about reentrancy before being asked |
| Reentrant check ordered first | You traced a self-deadlock in your head |
| `signalAll` justified by *predicate shape* | You reason about waiters, not idioms |
| Naming upgrade as unsafe unprompted | You've met the classic and know why it's unfixable |
| "This may be slower than a mutex" | You know when *not* to use your own answer |

## 7. What fails you

- Reaching for `ReentrantReadWriteLock` — that *is* the question.
- A single `int readerCount` when reentrancy was asked for.
- The writer-preference gate checked before the reentrant-read membership test.
- `signal()` where waiters have per-thread predicates.
- Counter incremented before a wait and decremented only on the happy path.
- Changing state in a `finally` without signalling.
- Claiming upgrade is safe "if you're careful".
- Not knowing that writer-preference starves readers, and not saying so.

## 8. Extensions

**"Make it fair — neither side starves."** → A **turnstile**: a FIFO gate every thread passes, which
a waiting writer *holds*, so new readers pile up behind it while in-flight readers drain.
*Trap:* `acquire()`/`release()` around the gate leaks a permit if interrupted between them.

```java
Semaphore mutex = new Semaphore(1, true);      // guards `readers`
Semaphore roomEmpty = new Semaphore(1, true);  // held by a writer, or by the FIRST reader
Semaphore turnstile = new Semaphore(1, true);  // FIFO gate

readLock()   { turnstile.acquire(); turnstile.release();
               mutex.acquire(); if (++readers == 1) roomEmpty.acquire(); mutex.release(); }
readUnlock() { mutex.acquire(); if (--readers == 0) roomEmpty.release(); mutex.release(); }
writeLock()  { turnstile.acquire(); roomEmpty.acquire(); }
writeUnlock(){ turnstile.release(); roomEmpty.release(); }
```

**"Make upgrade safe."** → You can't, not blocking and atomic. Offer `tryUpgrade()` returning
`false`, or release-then-reacquire. *Trap:* release-then-reacquire has a gap — a writer can slip in,
so you **must re-read state** afterwards. Callers forget this constantly.

**"What does the JDK do?"** → `ReentrantReadWriteLock`: reentrant, optional fairness, supports
downgrade, **forbids upgrade**. `StampedLock`: optimistic reads (`validate(stamp)` after reading, no
lock at all on the read path) plus `tryConvertToWriteLock`. *Trap:* `StampedLock` is **not
reentrant** — re-entering deadlocks against yourself.

**"Reads are 1000:1 — can we do better?"** → Optimistic reads, or per-shard locks so readers rarely
meet a writer. *Trap:* optimistic reads must tolerate seeing a **torn** object mid-write; copy into
locals and validate before using anything.

**"What if a thread dies holding the read lock?"** → Its map entry leaks and writers starve forever.
*Trap:* inherent to any `Thread`-keyed lock — `ReentrantReadWriteLock` behaves the same. Detect with
a leased/timeout variant, don't try to reclaim.

**"Give me `tryReadLock(timeout)`."** → Deadline once up front, `await(remaining)` in the loop.
*Trap:* on giving up you must decrement whatever you incremented **and signal** — the §9 bug again.

## 9. Bug log

| Bug | Symptom | Lesson |
|---|---|---|
| `signal()` on `writeWaitCondition` in `readUnlock` | **Deterministic deadlock.** A plain writer W and an upgrading reader T1 both wait; the last other reader leaves; `signal()` wakes the longest-waiting (W), which still can't proceed and re-sleeps. T1 was grantable and never woken | `signalAll` is required when the predicate **reads the asking thread**. `grantWriteAccess(Thread)` taking a parameter *is* the tell |
| `writeWaitCnt--` not in a `finally` | One interrupted writer inflated the counter forever → **every future reader blocked** on a free lock | A counter incremented before a wait must be decremented on *every* exit path |
| The `finally` decremented but didn't signal | Readers parked *because* `writeWaitCnt > 0` were never woken when it returned to 0; `readUnlock` only signals writers | **State change ⟹ signal.** Fixing a leak by moving code doesn't carry the signalling obligation with it — the fix created the next bug |

**The meta-lesson:** three bugs, three sites, one rule. Bug 2 was already documented as a known bug
in the *previous* Solution.md, fixed there, and then reintroduced in the cold rewrite — a recorded
bug you don't re-read is worth nothing.

## 10. Known limitations — deliberate trades

- **Reader starvation** under a steady stream of writers. Inherent to writer-preference; §8 has the fix.
- **Simultaneous upgrade deadlocks** rather than failing fast. Matches `ReentrantReadWriteLock`'s
  reason for forbidding upgrade outright; `tryUpgrade()` would be the safer API.
- **After an upgrade you hold BOTH locks** — `readingThread` still contains you. Forget `readUnlock()`
  and you leave a phantom reader that blocks every future writer.
- **A thread that dies holding a read lock leaks its entry** permanently.
- **`fair` only makes the internal mutex fair** — the grant policy stays writer-preference. It does
  *not* give FIFO ordering between readers and writers, which is what the name suggests.
- **No timeouts, no `tryLock`.**

## 11. Verified

Existing suite 3/3; all bugs above reproduced with a probe and a thread dump *before* the fix, and
re-run clean after.

**Covered:** core invariant under stress (8 readers × 3 writers × 4000 iterations × 3 rounds, with an
overlap detector inside both critical sections — no reader/writer overlap, no two writers, shared
counter exact 24000/24000) · upgrade/downgrade stress (6 readers, 4 writers, 3 downgraders, a
serialized upgrader — clean ×3) · reentrant read while a writer waits · `IllegalMonitorStateException`
on all four misuse paths · downgrade and reentrant-write both release cleanly · simultaneous upgrade
deadlocks as documented.

**Not covered:** starvation *bounds* (we show writers get in, not that readers eventually do) ·
`fair=true` ordering is never asserted · no test pins the §2 invariant that makes `writeUnlock`'s
`signal()` safe, so relaxing `grantWriteAccess` would break it silently · the existing suite passes
clean through all three bugs above, so it is happy-path only and needs invariant-style regressions.

## 12. 30-second recall

> **Many readers XOR one writer.** Writer-preference: a waiting writer blocks *new* readers so the
> count can reach zero. State is a **per-thread map**, not an `int` — reentrancy and "am I the sole
> reader" both need identity. **Reentrant-read check must come before the writer-preference gate**,
> or a re-entering reader self-deadlocks. **`signalAll` on the writer condition**, because
> `grantWriteAccess(Thread)` takes the asking thread — per-thread predicates can't be served by
> waking an arbitrary waiter. **Every write to `writer` / `writeWaitCnt` / `readingThread` owes a
> signal — including the decrement on the interrupt path.** Counter incremented before a wait is
> decremented in a `finally` **and signals**. **No safe blocking atomic upgrade** — `tryUpgrade` or
> release-then-reacquire-and-recheck; **downgrade is always safe** because you start exclusive.
> Fair = **turnstile**. JDK: `ReentrantReadWriteLock` forbids upgrade; `StampedLock` adds optimistic
> reads and isn't reentrant. And: **short critical sections make this slower than a plain mutex.**
