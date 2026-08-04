# Building H₂O — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §5.6.

## One-line idea

Threads are **hydrogen** or **oxygen**; they may only proceed grouped as **2 H + 1 O**, and the three
bond **together**. It's a **typed barrier**: a barrier of size 3 with a composition constraint. You
need two things — (1) admit exactly 2 H + 1 O per round, and (2) make all three leave together.

## This repo's solution (`BuildingH2O_Lock.java`) — verified correct

Monitor with **grant counters** + a **generation barrier**:

```java
void tryForm() {                                  // called after every arrival, under lock
    if (hReady >= 2 && oReady >= 1 && hGrant == 0 && oGrant == 0) {
        hReady -= 2; oReady -= 1;                  // CONSUME (nothing to reset later)
        hGrant = 2;  oGrant = 1;                   // hand out exactly 2 H + 1 O permits
        hCondition.signalAll(); oCondition.signalAll();
    }
}
// hydrogen(): hReady++; tryForm(); while (hGrant==0) hCond.await(); hGrant--; bond(); barrier();
// oxygen():   oReady++; tryForm(); while (oGrant==0) oCond.await(); oGrant--; bond(); barrier();

void barrier() {                                  // all 3 leave together
    int myGen = generation;
    if (++bonded == 3) { bonded = 0; generation++; doneCond.signalAll(); tryForm(); }
    else while (myGen == generation) doneCond.await();
}
```

Verified: every generation has exactly 3 members; terminates cleanly (each thread bonds once and exits).

## The two lessons (why the naive attempts failed)

### 1. Permits don't evaporate; flags do

A single `formingWater` boolean that the completer flips **on and off** in one lock hold is **not** a
barrier — the completer bonds and resets it before the others reacquire the lock, so they miss it and
hang. (Symptom seen: all O bonded, 0 H bonded, then freeze.)
**Fix:** hand out **grant counts** each waiter *consumes* (`while (hGrant==0) await; hGrant--`). A
consumed permit can't be missed, and `hGrant--` caps the group at exactly 2 H — a 3rd waiting H sees
`hGrant==0` and waits for the next molecule.

### 2. A reusable barrier needs a generation (round id)

After the 3rd member closes the molecule it calls `tryForm()`, which can **immediately** start the
next molecule and bump `bonded` again. A naive `while (bonded != 0)` straggler would then see the
*new* molecule's `bonded` and hang. Keying the wait on **`myGen == generation`** makes each straggler
wait for exactly *its own* round → immune to reuse.

### Also: form only on the FULL composition

Set the "go" only when `hReady >= 2 && oReady >= 1` — **not** when either count alone completes.
Firing on 2 H *or* 1 O lets incomplete molecules (2 H, no O) proceed.

## ⭐ Use the JDK — libraries that make this trivial

You almost never hand-roll a barrier in real code. The elegant standard solution:

```java
import java.util.concurrent.Semaphore;
import java.util.concurrent.CyclicBarrier;

Semaphore hSem = new Semaphore(2);          // at most 2 H per round  (composition)
Semaphore oSem = new Semaphore(1);          // at most 1 O per round  (composition)
CyclicBarrier barrier = new CyclicBarrier(3); // the 3 bond together   (rendezvous, auto-resets)

void hydrogen(Runnable bond) throws Exception {
    hSem.acquire();
    barrier.await();                        // wait for the other 2 H... err, for 2H+1O
    bond.run();
    hSem.release();                         // release AFTER the barrier so the next round waits
}
void oxygen(Runnable bond) throws Exception {
    oSem.acquire();
    barrier.await();
    bond.run();
    oSem.release();
}
```

Why it works: the **semaphores** cap each cohort at 2 H + 1 O; **`CyclicBarrier(3)`** blocks until all
three arrive, then trips and **auto-resets** for the next molecule; releasing the permits *after* the
barrier throttles the next round. This is the classic LeetCode "Building H2O" answer — ~10 lines.

| Primitive        | Role here                                   |
| ---------------- | ------------------------------------------- |
| **`Semaphore(2)` / `Semaphore(1)`** | enforce the 2 H : 1 O composition |
| **`CyclicBarrier(3)`**              | the 3-way rendezvous, **reusable** (auto-resets) |
| **`Phaser`**                        | more flexible reusable barrier; `register`/`arriveAndAwaitAdvance`; good when parties change dynamically |
| **`CountDownLatch`**                | ❌ one-shot, **not** reusable — wrong tool for repeated molecules |
| **`Exchanger`**                     | pairs of threads swap; doesn't scale to a trio |

Rule of thumb: **`CyclicBarrier`/`Phaser` = "N threads wait for each other and proceed together";
`CountDownLatch` = "wait for N events once, then done."** H₂O is repeated rendezvous → barrier, not latch.

Our hand-rolled `generation` barrier is essentially a from-scratch `CyclicBarrier` — good to know for
an interview that says "no `java.util.concurrent` barriers," but reach for the library otherwise.

## Things to keep in mind

- **`while`-guard every wait** (`while (hGrant == 0)`, `while (myGen == generation)`).
- **Consume, don't flag** — permits survive races; a shared boolean can be missed.
- **Reusable barrier ⇒ generation/round id** (or use `CyclicBarrier`, which handles it).
- **Ratio:** with `H:O ≠ 2:1`, leftover threads correctly **wait forever** (can't form a molecule) —
  validate `Hcnt == 2 * Ocnt` if you want `solve()` to terminate.
- Minor: make `lock`/`Condition` fields `final`.

## Generalization

Same shape works for any fixed composition (e.g. 3 A + 2 B): a typed barrier = per-type admission
(semaphore/grant count) + a size-`(a+b)` rendezvous. Cousin of Santa's "group of N" and River
Crossing's "boat of 4."

## 30-second recall

> 2 H + 1 O bond together = **typed barrier**. Admit exactly 2 H + 1 O via **grant counts you
> consume** (permits don't evaporate; flags do); make them leave together via a **generation barrier**
> (reuse-safe). Form only when `hReady>=2 && oReady>=1`. In real code: **`Semaphore(2)` + `Semaphore(1)`
> + `CyclicBarrier(3)`**. Barrier for repeated rendezvous, `CountDownLatch` only for one-shot.
