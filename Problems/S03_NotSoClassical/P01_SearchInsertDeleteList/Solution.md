# Search–Insert–Delete — Solution

> Book ref: *The Little Book of Semaphores* §6.1. Rubrik-style "3-state access control".
> Two locks here: **`SearchInsertDeleteLock`** (monitor, deleter-preference) and
> **`FairSearchInsertDeleteLock`** (FIFO queue, no starvation). The list is shared by both.

## 1. The idea

Three roles, one compatibility matrix. Encode the matrix with per-role state (**scheduling**), and
publish the node fields yourself (**visibility**) — because the whole point is that search and insert
overlap with no common lock held.

|              | Searcher | Inserter | Deleter |
| ------------ | :------: | :------: | :-----: |
| **Searcher** |    ✅    |    ✅    |    ❌   |
| **Inserter** |    ✅    |    ❌    |    ❌   |
| **Deleter**  |    ❌    |    ❌    |    ❌   |

## 2. State model

**Preference lock** — 4 fields carry the whole policy:

| Field | Meaning |
|---|---|
| `activeSearchers` / `activeInserter` / `activeDeleter` | who is inside |
| `waitingDeleters` | the deleter-preference gate — **the only waiting-counter another role's predicate reads** |

`waitingSearchers` / `waitingInserters` exist in the current file but appear in **no** wait predicate;
they only decide whether a `signal` is worth making. That asymmetry drives §5.

**Fair lock** — the policy collapses to one function and one queue:

| Field | Meaning |
|---|---|
| `Queue<Node>` | every request in arrival order; each `Node` holds its own `Condition` and a `granted` flag |
| `activeSearchers` / `activeInserter` / `activeDeleter` | same three |
| `compatible(TYPE)` | **is** the matrix, six lines, directly checkable against the table |

## 3. Mechanism, and the traps

- **`grant()` walks from the front and stops at the first incompatible request.** Skipping it would
  admit more work and be faster — and would silently restore starvation, because a later compatible
  request could overtake an earlier blocked one forever. **Stopping is the entire fairness
  guarantee**, and it reads like a missed optimisation to anyone who doesn't know that.
- **`addActive` runs as `grant()` walks**, so `[SEARCH, SEARCH, INSERT]` admits all three in one pass.
- **A decrement owes a signal only if that variable appears in someone's wait predicate.**
  `waitingDeleters--` opens a gate two other roles block on → owes a signal. `waitingSearchers--` /
  `waitingInserters--` owe nothing.
- **`while`, not `if`, around every await — because of barging**, not spurious wakeups. See §9.
- **Wake searchers with `signalAll`.** They're the one mutually-compatible role: when a deleter
  leaves, every waiting searcher becomes grantable at once.
- **The traversal runs outside the lock by design**, so scheduling correctness buys you nothing about
  visibility. §5.3.
- **Neither lock is composable.** Holding one role and acquiring another can deadlock. §9.

## 4. What to ask the interviewer

1. "Delete:search ratio? It decides whether deleter-preference is free or catastrophic."
2. "Does a pending delete have a deadline — expiry, revocation? That's what makes preference worth its cost."
3. "Must every role have bounded waiting, or just no deadlock?"
4. "Can one thread hold two roles — search then insert? That changes everything."
5. "Insert at the tail only, or anywhere? Mid-list insert breaks the concurrent-search argument."

## 5. Answers to Problem.md §7

1. **Four fields** for the preference version: three active + `waitingDeleters`. The FIFO version
   needs the three active plus the queue; `compatible()` then *is* the matrix.
2. **Searcher × Inserter = ✅.** A two-mode lock has no state in which a writer and readers coexist,
   so it must either serialise them (killing the concurrency) or admit a deleter beside a searcher.
3. **Scheduling only — visibility needs `volatile`.** `head` and `Node.nxt` are both written by an
   inserter and read by a searcher with no common lock. `val` needs nothing extra: the volatile write
   to `nxt` happens-before the searcher's volatile read of `nxt`, so everything the inserter did
   earlier — including setting `val` — is visible transitively. `final val` is still worth writing:
   free, documents immutability, survives future publication paths.
4. **Tail append is a single pointer write** — a searcher sees either the old `null` or the fully
   built node, and both are legal linearisations. **Middle insert rewires a link a searcher may be
   standing on**, so `volatile` alone no longer saves you.
5. **Deleter-preference starves searchers and inserters**, and not marginally. Measured in §11: a
   steady delete stream drops searches from 13M/s to **4/s** with a **2005 ms** worst-case wait.
6. It also **creates a deadlock**: a thread holding search that asks for insert blocks on
   `waitingDeleters > 0`, while the deleter blocks on that thread's `activeSearchers > 0`. Fix both
   with the FIFO queue — there is no gate for a held role to block against.
7. **If another deleter waits, signal it; otherwise `signalAll` searchers *and* signal one inserter.**
   Those two are compatible with each other, so both may resume together.
8. **Three conditions** for the preference version (one per role). The fair version needs **one per
   request**, because `grant()` must wake a *specific* queued node.
9. **Nothing sensible** — neither lock tracks ownership, so a bogus `unlockDelete()` silently
   corrupts the counters. Ownership tracking (a `Thread` field) would be needed to throw.
10. Instrument active counts around the lock and assert **continuously**, not at exit:
    `activeDeleter ⇒ searchers == 0 && !inserter`, and `inserters ≤ 1`. That is exactly the harness in §11.
11. Separation lets you **stress the policy with no list at all** — every measurement in §11 drives
    the lock directly. The cost is two objects to keep consistent, and the lock can never make a
    decision based on the data (e.g. "delete only touches a key nobody is reading").

## 6. What the interviewer is checking

| Signal | What it proves |
|---|---|
| Naming the Searcher×Inserter cell as the one RWLock can't express | You read the matrix, not the label |
| Separating scheduling from visibility unprompted | The senior signal on this problem |
| Stating the starvation policy *and its cost* | You know preference relocates starvation, not removes it |
| "A thread must hold at most one role" | You spotted the composability trap |
| `while` justified by barging | You've debugged this, not memorised it |
| Testing by instrumenting active counts | You can prove a matrix, not just assert it |

## 7. What fails you

- Reaching for `ReentrantReadWriteLock` — two states can't express three.
- Holding the lock across the traversal: satisfies the matrix, destroys the concurrency the problem exists for.
- `if` instead of `while` on any entry guard.
- `signal` where searchers wait — they're the one role that must all wake.
- A counter incremented before a wait and decremented only on the happy path.
- Saying "deleter-preference" without saying who now starves.
- Claiming the concurrent search/insert is safe because the lock is correct.

## 8. Extensions

**"Make it fair."** → FIFO queue of requests, each with its own condition; `grant()` walks from the
front and stops at the first blocker. *Trap:* skipping incompatible entries to admit more work
silently reintroduces starvation.

**"Insert in the middle, not just the tail."** → Now a searcher can be standing on the link being
rewired. *Trap:* `volatile` does not help; you need insert to exclude searchers, or hazard
pointers / RCU.

**"Let searchers run during deletes."** → Lock-free territory: logical deletion (mark), then reclaim
once no reader can hold a reference. *Trap:* reclamation is the hard part — that's what hazard
pointers and epoch-based reclamation exist for.

**"Can a thread upgrade search → insert?"** → No, not blocking and atomic. *Trap:* the JDK agrees —
`ReentrantReadWriteLock` upgrade **self-deadlocks** rather than throwing (§9). Offer `tryLockInsert()`
or release-then-reacquire-and-revalidate.

**"Bound memory in the fair version."** → Each acquisition allocates a `Node` + a `Condition`.
*Trap:* pooling them reintroduces the question of when a condition is safe to reuse.

## 9. Bug log

| Bug | Symptom | Lesson |
|---|---|---|
| `lockDelete` used `if`, not `while` | **Two deleters active simultaneously**, 3/3 runs | Not a spurious wakeup — **barging**. D1 signals D2; before D2 re-acquires, a fresh D3 finds the state clean, skips the wait entirely, and both end up inside |
| `unlockDelete` used `signal()` on the searcher condition | 4–5 of 6 searchers **parked forever**; isolated by fixing `while` first and watching them stay stuck | Searchers are the one *mutually compatible* role — a deleter leaving makes them all grantable, so it is a genuine broadcast. `signal` there doesn't cost throughput, it strands threads |
| `waitingDeleters--` skipped when `await()` threw | One interrupted deleter **bricked the lock** — every future searcher and inserter gated out forever | The gate counter must be decremented on every exit path |
| …and the `finally` alone wasn't enough | Waiters already parked *because* `waitingDeleters > 0` were never woken when it returned to 0 | **The decrement owes a signal — but only on the path that actually opens the gate.** Signalling in the `finally` also fires on the success path, waking every searcher for nothing |
| Fair lock: granted-then-interrupted | `activeSearchers` leaked 1–2 per round, **lock wedged 3 of 4 runs**; acquisitions collapsed from ~1,200 to ~35 | `grant()` does `addActive` → `setGranted` → `signal`. A thread interrupted *between* set and signal cancels its condition node, so AQS throws `THROW_IE` even though it was granted. Honour the grant: re-interrupt and return, so the caller reaches `unlock` |
| Search → insert while a deleter waits | **Deterministic deadlock**, 3/3 | Deleter-preference creates a hold-and-wait edge. **The starvation fix created a deadlock** — the two properties trade against each other |
| *(my review)* Proposed signalling inside the `finally` | He pushed back: it fires on the success path too | Right — O(N) wasted wakeups per acquisition, and `waitingDeleters == 0` is the *common* case for a solo deleter. The signal belongs on the exception path |
| *(my probe)* `SidInt` "passed" on unfixed code | The new searcher arrived **after** the deleter abandoned, so it never parked and never needed a signal | A test that exercises the recovery path must first get a thread **into** the state being recovered from |

## 10. Known limitations — deliberate trades

- **`SearchInsertDeleteLock` starves searchers under delete load** (§11) and **deadlocks on
  search→insert**. Both are inherent to preference, not bugs.
- **Neither lock tracks ownership**, so a mismatched `unlockX()` corrupts the counters silently.
- **Neither is reentrant** and neither is composable — one role per thread.
- **`val` is not `final`** — correct as-is thanks to `volatile nxt`, but a free robustness win.
- **Two dead `catch` bodies** remain in the preference lock: signals for predicates nobody waits on.
- **Fair lock allocates** a `Node` + `Condition` per acquisition; `grant()` is O(queue prefix).

## 11. Verified

Both locks stressed directly, with active counts instrumented around every enter/exit and asserted
**continuously** (`deleter ⇒ nothing else`, `inserters ≤ 1`).

**Matrix:** clean for both locks — 4/4 runs (preference), 3/3 (fair), 6 searchers × 3 inserters ×
2 deleters, thousands of iterations each, no illegal overlap and no stuck threads.

**Starvation, measured** — 6 searchers against a steady delete stream:

| deleters | preference: searches/s | max wait | fair: searches/s | max wait |
|---|---|---|---|---|
| 0 | 13,234,793 | 0 ms | 13,197,330 | 0 ms |
| 1 | 1,128 | 791 ms | 22,977 | 1 ms |
| 4 | 1,888 | 2005 ms | 6,776 | 1 ms |
| 8 | **4** | **2005 ms** | **4,143** | **2 ms** |

(Absolute numbers are lock-overhead-bound — read the ratios.)

**Interrupts:** ~1,200 interrupts/round against the fair lock leaves `activeSearchers = 0` and an
empty queue, 5/5 rounds, and a fresh deleter still acquires, 4/4 rounds. Preference lock recovers on
both the parked-waiter and fresh-arrival paths.

**JDK comparison, measured:** `ReentrantReadWriteLock` read→write upgrade **blocks forever** (`WAITING`,
single thread, no exception); `writeLock().tryLock()` returns `false`; write→read downgrade works;
`StampedLock.tryConvertToWriteLock` succeeds for a sole reader and returns `0` — never blocks — with a
second reader present.

**Not covered:** the visibility fix is reasoned, not measured — data races don't reproduce reliably,
so `volatile head` / `volatile nxt` rest on the JMM argument in §5.3 · no test pins "one role per
thread", so a future caller can reintroduce the search→insert deadlock silently · mid-list insert is
not implemented or tested.

## 12. 30-second recall

> Three roles, one matrix: **many searchers ‖ one inserter overlap; a deleter runs alone.** The cell
> `ReentrantReadWriteLock` can't express is **Searcher × Inserter** — a writer that tolerates readers
> is a third state. **Scheduling ≠ visibility:** the traversal runs outside the lock, so `head` and
> `Node.nxt` must be `volatile`; `val` rides along on the volatile write. `while` not `if` — because
> of **barging**. `signalAll` for searchers (all grantable at once), `signal` for the other two.
> **Deleter-preference relocates starvation, it doesn't remove it** — measured 4 searches/s vs 4,143
> under FIFO — and it creates a **search→insert deadlock**, because a held role blocks the deleter
> that blocks the held role. **A decrement owes a signal only if that variable is in someone's wait
> predicate**, and only on the path that actually opens the gate. Fair version = FIFO queue, one
> condition per request, and `grant()` **stops at the first incompatible request** — that stop *is*
> the fairness guarantee. No blocking atomic upgrade exists: the JDK's own RWLock self-deadlocks.
