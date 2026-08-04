# Dining Savages — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §5.1. This repo uses a **monitor** (`ReentrantLock` +
> two `Condition`s), not the book's semaphores.

## One-line idea

Shared pot of `M` servings, many savages, one cook. Savage takes a serving if the pot is non-empty;
if empty, it wakes the cook and waits. Cook refills to `M` when empty, then sleeps. Guard the count
with one lock; use a predicate + `while` so signals are advisory, not load-bearing.

## Core template (this repo)

```java
int servings = 0;                       // starts empty -> cook fills first
Lock lock; Condition cookCond, savageCond;

// Cook
while (running) {
    lock.lock();
    try { while (servings > 0) cookCond.await(); }   // park while pot has food
    finally { lock.unlock(); }

    cook();                              // SLOW work OUTSIDE the lock

    lock.lock();
    try { servings = M; savageCond.signalAll(); }     // refill, wake everyone
    finally { lock.unlock(); }
}

// Savage
while (hungry) {
    lock.lock();
    try {
        while (servings == 0) { cookCond.signal(); savageCond.await(); }  // empty -> wake cook, wait
        servings--;                      // take one
    } finally { lock.unlock(); }
    eat();                               // OUTSIDE the lock
}
```

## Why it's correct

- **`while`-guards make signals advisory.** The cook parks on `while (servings > 0)`. When savages
  drain the pot to 0 silently (no signal on the decrement), the cook either (a) is parked and gets
  woken by the next hungry savage's `cookCond.signal()`, or (b) is racing for the lock, re-checks
  `servings > 0` → false, and **cooks anyway**. A lost signal never hangs it. ✅
- **No deadlock is possible.** A hang needs cook parked + all savages parked + `servings == 0`. But a
  savage parks only _after_ signalling the cook — which wakes a parked cook. So that state can't
  arise. ✅
- **Cook the slow part OUTSIDE the lock.** Never hold a lock across `sleep`/I/O. Safe here because
  while `servings == 0`, savages are blocked at the `== 0` guard and can't mutate it. ✅
- **Refill exactly once per empty event** — after `servings = M` the cook loops back and parks. ✅

## Design choice to name in interview

The savage that takes the **last** serving does **not** signal the cook. The cook is woken by the
**next** hungry savage that finds the pot empty → _signal-on-find-empty_, not _signal-on-cause-empty_.
This is lazy cooking (don't cook until someone's hungry) — valid and arguably better.

## Terminating cleanly (the part the infinite version skips)

**General rule (memorize):** a `running`/`done` flag alone is **not** enough if threads block on
`await()`. On shutdown you must also **wake every blocked waiter** so each re-checks its predicate,
sees the flag, and exits. Flag **and** broadcast.

### Approach used here — bounded meals + finished-counter (your idea)

Give each savage a fixed number of meals; when all savages finish, flip a `done` flag and wake the
cook (which may be parked).

```java
int mealsPerSavage;          // each savage eats this many, then leaves
int finished = 0;            // guarded by lock
boolean done = false;

// Savage
for (int i = 0; i < mealsPerSavage; i++) { getServing(); eat(); }
lock.lock();
try {
    if (++finished == N) {   // last one out
        done = true;
        cookCond.signal();   // wake the cook in case it's parked on `while (servings > 0)`
    }
} finally { lock.unlock(); }

// Cook — check `done` in the predicate AND after waking
while (true) {
    lock.lock();
    try {
        while (servings > 0 && !done) cookCond.await();
        if (done) return;            // shutdown path
    } finally { lock.unlock(); }
    cook();
    lock.lock();
    try { servings = M; savageCond.signalAll(); }
    finally { lock.unlock(); }
}
```

Why it works:
- Total demand is fixed (`N * mealsPerSavage`); the cook keeps refilling on demand, so no savage
  blocks forever waiting for food.
- The **last** savage to finish sets `done` and signals `cookCond`, so a parked cook wakes, sees
  `done`, and returns instead of cooking again.
- Now `solve()`'s `join()` actually returns — the test can assert.

**Watch out:** the cook may be mid-`cook()` (sleeping outside the lock) when the last savage finishes.
That's fine — it completes the refill, loops, checks `done`, and exits. One possibly-wasted final
refill; harmless.

### Other shutdown options

- **Daemon threads** (`t.setDaemon(true)`) — JVM exits when only daemons remain. Zero code, but no
  deterministic join → useless when a test must assert.
- **Interrupt-based** (`ExecutorService.shutdownNow()` / `thread.interrupt()`) — let
  `InterruptedException` end the loop. Cleanest with pools, but **fix the swallow-and-continue**: an
  interrupt during `await()` must `return`, not fall through into a spurious refill.

## Gotchas

- The infinite version's cook catches `InterruptedException` then **continues** → spurious refill +
  can't be stopped. For a terminating version, `return` right after the guarded `await`.
- `signal()` for the cook is fine (single waiter); `signalAll()` for savages is required (many
  waiters, and only `M` can proceed).
- Make `lock`, conditions, `N`, `M` `final`; name savage threads `"Savage-" + id`.

## 30-second recall

> One pot (`M`), one lock, two conditions. Savage: `while(servings==0){ signal cook; await }` then
> `servings--`. Cook: `while(servings>0) await;` then cook **outside the lock**, refill to `M`,
> `signalAll`. `while`-guards make lost signals harmless → no deadlock. Terminate with fixed meals +
> a `finished` counter; last savage sets `done` and wakes the parked cook. **Flag + wake all
> waiters**, always.
