# River Crossing — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §5.7. Sibling of Building H₂O (§5.6).

## One-line idea

Hackers and serfs board a boat of **4**, but only in a **valid crew**: `4H`, `4S`, or `2H+2S`.
It's a **typed barrier** — like H₂O, but with **three** valid compositions instead of one, which makes
the "who completes a boat?" logic (and the invariant that keeps cohorts from mixing) trickier.

## Structure (this repo)

Grant counters + a generation barrier (same skeleton as H₂O):

```java
void tryBoarding() {                              // called on every arrival AND after each boat departs
    if (hackerRelease != 0 || serfRelease != 0) return;   // ⭐ NO BOAT IN FLIGHT (see below)
    if      (serfWaiting >= 4)                     { serfWaiting-=4;  serfRelease+=4;  serfCond.signalAll(); }
    else if (hackerWaiting >= 4)                   { hackerWaiting-=4; hackerRelease+=4; hackerCond.signalAll(); }
    else if (hackerWaiting >= 2 && serfWaiting>=2) { hackerWaiting-=2; serfWaiting-=2;
                                                     hackerRelease+=2; serfRelease+=2;
                                                     hackerCond.signalAll(); serfCond.signalAll(); }
}
// hacker(): hackerWaiting++; tryBoarding(); while(hackerRelease==0) hackerCond.await(); hackerRelease--; row?; barrier();
// serf():   serfWaiting++;   tryBoarding(); while(serfRelease==0)   serfCond.await();   serfRelease--;   row?; barrier();

void barrier() {                                   // all 4 leave together
    int myGen = generation;
    if (++onBoard == 4) { onBoard=0; generation++; onBoardCond.signalAll(); tryBoarding(); }
    else while (myGen == generation) onBoardCond.await();
}
```

## ⭐ The one invariant that makes it correct: ONE cohort in flight at a time

**The bug that bites:** *release* (handing out permits in `tryBoarding`) and *grouping* (`barrier()`
counting any 4 arrivals) are **decoupled**. If `tryBoarding` releases cohort A (a `4H` boat) and then,
before A finishes boarding, releases cohort B (a `4S` boat), the two cohorts **interleave at the
barrier** → an invalid `3H+1S` boat. Silent (wrong composition, not a hang) and timing-dependent.

**The fix (one line):** don't form a new boat while permits are still outstanding:

```java
if (hackerRelease != 0 || serfRelease != 0) return;   // no boat in flight
```

**Why it works:** each released person does `release--` and **immediately** `onBoard++` in the same
lock hold (no `await` between). So `release` only reaches 0 at the instant the **last** member takes
its permit — and that member, still holding the lock, completes the boat (`onBoard==4` →
`generation++`) and calls `tryBoarding` for the next cohort. Nobody can slip a new cohort in between.
Cohorts are strictly serialized ⇒ members never mix ⇒ composition always valid.

> This is **exactly** the H₂O invariant (`hGrant==0 && oGrant==0`). River had dropped it — that's the
> whole difference. **Typed barrier ⇒ admit one cohort at a time.**

## Bugs found in this implementation

| Bug | Symptom | Fix |
| --- | ------- | --- |
| `hackerRelease -= 4` (typo; should be `+=`) | release count goes negative → hacker gate never re-blocks | `hackerRelease += 4` |
| No "in-flight" guard in `tryBoarding` | cohorts interleave at barrier → **invalid `3H+1S` boats** (timing-dependent, silent) | `if (hackerRelease!=0 || serfRelease!=0) return;` |

Both verified: with the guard + typo fix → 0 invalid boats, 0 hangs across many mixed-supply runs.

## Termination: is a waiting thread a bug? (interview framing)

A thread waiting for a valid crew is **correct behavior**, not a hang. The algorithm's job is to
*never form an invalid boat*; if the supply can't be partitioned (e.g. leftover `2H`, no serfs), those
threads *should* wait. Handle it by **model**, not by cheating:

- **Continuous stream (classic model):** arrivals never stop ⇒ a waiter just waits for compatible
  peers. No real hang. Say this first.
- **Finite batch + must terminate:** add a **shutdown** (flag + wake-all — the Dining Savages
  pattern), so stragglers exit cleanly instead of boarding an illegal boat:

```java
void close() { lock.lock(); try { closed = true; hackerCond.signalAll(); serfCond.signalAll(); } finally { lock.unlock(); } }
// wait loop: while (hackerRelease == 0 && !closed) hackerCond.await();  if (closed && hackerRelease==0) return;
```

- **Or validate up front** that the supply is partitionable — usually over-engineering; mention it.

> The interviewer is checking: can you tell "algorithm stuck" (bug) from "input unsatisfiable, so
> blocking is correct" (not a bug)? Naming that distinction is the point.

## ⭐ Use the JDK — the library-assisted version

You rarely hand-roll the barrier. Natural fit: **`Semaphore` per composition slot + `CyclicBarrier(4)`
with a barrier action** (the barrier action is a clean way to get "exactly one rower" per trip):

- A **mutex** guards the counts; the arriving thread that completes a valid `{4H, 4S, 2H+2S}` is the
  **captain**: it releases exactly the right 4 permits (`Semaphore.release`) and resets counts.
- **`CyclicBarrier(4, () -> row())`** rendezvouses the 4 and runs `row()` **once** (barrier action),
  then auto-resets for the next boat.

| Primitive | Role |
| --------- | ---- |
| `Semaphore` (per type) | admit exactly the crew the captain releases |
| `CyclicBarrier(4, rowAction)` | 4-way rendezvous, **one rower** via barrier action, auto-reset |
| `Phaser` | flexible reusable barrier if parties change dynamically |
| `CountDownLatch` | ❌ one-shot — wrong tool for repeated boats |

The hand-rolled `generation` barrier here is a from-scratch `CyclicBarrier` — good for a
"no `java.util.concurrent` barriers" interview; otherwise reach for the library.

## Things to keep in mind

- **One cohort in flight** — guard the group-former with `release == 0` (the H₂O invariant).
- **`while`-guard every wait**; **generation** id makes the reusable barrier straggler-safe.
- **Captain = the arrival that completes a valid 4** — it releases and resets ("last one flips the switch").
- **Waiting ≠ deadlock** — unsatisfiable supply *should* block; terminate via shutdown flag if needed.
- Minor: `Condition` fields `final`.

## 30-second recall

> Boat of 4, valid crews `4H / 4S / 2H+2S` = **typed barrier, 3 shapes**. Release exactly a valid
> crew via grant counts, group them with a **generation barrier**. Critical invariant: **one cohort
> in flight** (`release == 0` guard) or cohorts mix → `3H+1S`. A thread waiting on an unsatisfiable
> supply is **correct**, not stuck — terminate with a shutdown flag if the model is finite. Library:
> `Semaphore` + `CyclicBarrier(4, rowAction)`.
