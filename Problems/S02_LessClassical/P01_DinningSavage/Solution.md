# Dining Savages — Solution

> Book ref: *The Little Book of Semaphores* §5.1. This implementation: one `ReentrantLock`, two
> conditions, a `cookRequested` coalescing flag, cooking done outside the lock.

## 1. The idea

Many consumers drain a pot one serving at a time; one producer refills it to `M` in a single batch.
**Broadcast on the way back in, coalesce on the way out.**

## 2. State model

| Field | Meaning |
|---|---|
| `currentServings` | 0..`M`. Savages decrement; the cook is the only writer that raises it |
| `cookRequested` | "the cook has already been asked" — collapses N notices into one refill |
| `cookSleepCondition` | the cook waits here for `currentServings == 0` |
| `savageWaitCondition` | savages wait here for `currentServings > 0` |

**The invariant that makes the design safe**, and which is nowhere in the code:

> Savages only ever *decrement*, and only when `currentServings > 0`. The cook is the sole
> incrementer, and there is exactly one cook.

Everything below depends on it — including why the cook does **not** need to re-check emptiness after
cooking (§9).

## 3. Mechanism, and the traps

- **Two conditions, two different signal choices, both forced by the predicate.**
  `savageWaitCondition.signalAll()` — one refill of `M` satisfies up to `M` waiters, so it's a genuine
  broadcast. `cookSleepCondition.signal()` — a single waiter with one predicate.
- **Coalescing is the hard half.** Every savage arriving during the dry spell sees an empty pot. Without
  `cookRequested`, each one signals and you queue N refills for one exhaustion. The flag must be
  read-and-set under the same lock as the emptiness test.
- **The cook resets `cookRequested`, not the savage** — it clears at the moment the pot becomes
  non-empty, together with `currentServings = potCapacity` and the broadcast, all under one lock.
- **Cook outside the lock.** The 1s cook is done with the lock released. Holding it would block every
  savage for the whole cook, and would serialise the one part that has nothing to do with shared state.
- **`catch (InterruptedException) { interrupt(); }` must be followed by leaving the loop.** Restoring
  the flag and continuing is a spin generator — every later `await`/`sleep` throws instantly and
  re-arms itself. This produced 903,856 refills in 1.2 s (§9).
- **Loop conditions are worth reading twice.** `while (Thread.currentThread().isInterrupted())` and
  `while (!...)` look alike and fail completely differently.

## 4. What to ask the interviewer

1. "Do savages and the cook run forever, or is there a shutdown?"
2. "Is the cook allowed to refill a partly-full pot, or only an empty one?"
3. "Does fairness matter — must every savage eventually eat, or just no deadlock?"
4. "One cook or several? That changes whether the refill needs to re-verify emptiness."
5. "May I use `java.util.concurrent`, or do you want `wait`/`notify`?"

## 5. Answers to Problem.md §7

1. **Test-and-decrement must be atomic.** Otherwise two savages both read `1`, both decrement, and the
   count reaches **−1** — a serving eaten that never existed. Both steps under the same lock.
2. **Exactly one refill per exhaustion.** You need a boolean ("already asked"), read and set under the
   same lock as the emptiness test. Without it, N savages ⇒ N refills for one empty pot.
3. **All of them** — `signalAll`. One refill produces `M` servings, so up to `M` waiters can now
   proceed. `signal` would wake one and leave the rest asleep on a full pot.
4. **`signal`** for the cook. Different answer from Q3, and the reason is the *shape*, not the count:
   the cook is a single waiter on a single predicate, so one wakeup is sufficient and exact.
5. **Not lost.** The waiting condition is a *durable* variable (`currentServings`), re-tested inside a
   `while`. A savage that arrives after the refill simply finds the pot non-empty and never waits.
   Signals are a hint; the state is the truth.
6. **The cook clears it**, at the instant the pot becomes non-empty. If a savage cleared it — say on
   waking — two savages could both clear-and-set and you'd get a second refill for one exhaustion.
7. **No.** Holding the lock while cooking blocks every savage for the entire cook and serialises work
   that touches no shared state. The cost of *not* holding it is that the emptiness observation is
   stale by the time you refill — which is harmless here, and only harmless because of §2.
8. **Not in practice, and not guaranteed either.** `signalAll` plus a non-fair lock gives no ordering
   promise, though measurement showed a perfectly even split (6 savages, 3 servings each). To *bound*
   it you'd need a fair lock or an explicit queue.
9. Two differences: production is **batched** ("fill to `M`", not "add 1"), and the consumer is the one
   who **triggers** production. The batching is what forces `signalAll` — in a blocking queue each
   `put` creates exactly one item, so waking exactly one consumer is right.
10. `M = 1` works (pot empties every take, one refill per serving). `M = 0` livelocks — the cook
    refills to 0 forever and nobody eats. `M < 0` is worse: the guard `currentServings == 0` is false
    for `-1`, so savages decrement into `-2, -3, …` and eat from a pot that never had food. Both are
    why the constructor validates.
11. The component form is `takeServing(int id)` / `refill()` — the caller supplies threads. `solve()`
    owning its own threads makes this a **simulation**; a system coding round wants the callable pair.

## 6. What the interviewer is checking

| Signal | What it proves |
|---|---|
| Coalescing the wake-the-cook request | You spotted that N notices ≠ N refills |
| `signalAll` one way, `signal` the other, each justified | You reason from the predicate, not from habit |
| Slow work outside the critical section | You know a lock is for state, not for duration |
| "Signals are a hint, state is the truth" | You understand why lost wakeups don't bite here |
| Validating `M <= 0` | You check the degenerate cases unprompted |
| Naming this as a pool with batch refill | You can generalise past the fable |

## 7. What fails you

- One condition for both parties, so a savage's wakeup can be consumed by the cook.
- `signal` after a refill — `M` servings, one savage woken.
- Signalling the cook from every savage that notices an empty pot.
- Testing emptiness and decrementing under different lock acquisitions.
- Holding the lock across the cook.
- `catch (InterruptedException)` that restores the flag and keeps looping.
- `== 0` instead of `<= 0` as the emptiness guard, so a corrupted count runs away instead of stalling.
- No termination path at all.

## 8. Extensions

**"Multiple cooks."** → Now the refill **must** re-check `currentServings == 0` after re-acquiring the
lock. *Trap:* the §2 invariant is what made the re-check unnecessary with one cook; adding a second
cook silently invalidates it, and nothing in the code records that dependency.

**"Bound the wait — no savage starves."** → Fair lock, or an explicit FIFO queue of hungry savages.
*Trap:* `signalAll` plus a fair lock still doesn't give FIFO *service*, only FIFO *acquisition*.

**"Savages take k servings at once."** → Predicate becomes `currentServings >= k`. *Trap:* waiters now
have **different** predicates, so `signalAll` becomes mandatory and a woken savage may still not fit.

**"The cook can be slow — don't block savages who could still eat."** → Refill incrementally rather
than at the end. *Trap:* then partial refills must signal as they go, and "refill only when empty"
stops being true.

**"Turn it into a component."** → `takeServing(id)` / `refill()`, threads supplied by the caller.
*Trap:* `refill()` must be callable in a loop and must not assume it is the only cook (see above).

**"Shut it down cleanly."** → Interrupt every thread; each loop exits on the restored flag.
*Trap:* a savage interrupted mid-`takeServing` must report that it took **nothing**, or the caller
"eats" a serving that was never removed.

## 9. Bug log

| Bug | Symptom | Lesson |
|---|---|---|
| Cook's `catch` restored the interrupt flag and **fell through** into the refill | **903,856 refills in 1.2 s**, thread `RUNNABLE`, CPU pegged. Each restored flag made the next `await`/`sleep` throw instantly | Restore-and-continue is a spin generator. `interrupt()` in a catch must be followed by `return`/`break` |
| Same fall-through reached the refill **without ever observing an empty pot** | Cook interrupted while waiting on a pot holding **1** serving; pot jumped to **5**. Four servings from nowhere | The bug wasn't a stale observation — it was *no* observation. Don't guard a path that was never meant to be reached |
| Savage's `catch` used `printStackTrace()` and dropped the flag | Returned without decrementing, then `eat()` ran anyway — a phantom meal; thread unstoppable | Two halves of one class had **opposite** interrupt policies. Pick one and apply it to every loop |
| No constructor validation | `M = 0` livelocks; `M = -1` lets savages decrement to −2, −3, … and eat from an empty pot | `== 0` as an emptiness guard lets a corrupted count run away; `<= 0` stalls instead |
| `while (Thread.currentThread().isInterrupted())` — missing `!` | **Total deadlock.** Cook thread exited before doing anything; savages drained the initial pot, then all parked forever. `cook alive=false, refills=0` | An inverted loop condition presents as a *synchronisation* bug. Checking whether the thread was even **alive** is what separated the two — and this same character was lost once in my own patch script during this session |
| *(my review)* Claimed the refill needed an `if (currentServings == 0)` re-check | Wrong — he pushed back, and measurement agreed: the `return` alone fixed it | With one cook and decrement-only savages, the observation **cannot** go stale. I pattern-matched to check-then-act on code that didn't have that shape. Verify the invariant before naming the race |

## 10. Known limitations — deliberate trades

- **Simulation, not a component.** `solve()` owns the threads; only the constructor and `solve()` are
  public. The callable pair (`takeServing`/`refill`) is §8.
- **No fairness guarantee.** Even distribution was measured, not enforced.
- **Fixed 1 s cook and 500 ms eat**, hard-coded — no injectable clock, so timing can't be tested.
- **`solve()` never returns** on its own; shutdown requires the caller to interrupt the threads.
- **Logging on the hot path** — every take/eat prints under or near the lock.

## 11. Verified

All figures below are from the corrected file (with the `!` restored at line 35); **the file as it
stands deadlocks**, see §9.

**Covered** — 6 s run, capacity 3, 6 savages: 6 cook requests → 5 refills (**one refill per request —
coalescing works**) · 18 servings taken, exactly `refills × capacity + initial capacity` · a watchdog
sampling `currentServings` throughout found **no** value below 0 or above capacity · every savage ate
(3 each) · after interrupting all threads, **0 alive** 1.5 s later.

**Not covered:** starvation bounds under an adversarial schedule · behaviour with more than one cook
(§8 — and the §2 invariant would no longer hold) · `M = 1` and large-`N`/small-`M` contention were
reasoned through, not measured · no test pins the §2 invariant, so a second cook would break the
design silently.

## 12. 30-second recall

> Pot of `M` servings, many savages, one cook. **Pool with batch replenishment.** Two conditions:
> savages wait for `servings > 0`, cook waits for `servings == 0`. **`signalAll` to savages** (one
> refill feeds `M` of them) but **`signal` to the cook** (single waiter, single predicate) — the
> predicate's shape decides, not the waiter count. **Coalesce the request**: a `cookRequested` flag,
> set under the same lock as the emptiness test, or N savages noticing ⇒ N refills. The **cook**
> clears it, when the pot becomes non-empty. **Cook outside the lock.** No lost wakeup because
> `currentServings` is durable and re-tested in a `while` — *signals are a hint, state is the truth*.
> Test-and-decrement must be atomic or the count goes **−1**. Guard with `<= 0`, not `== 0`. Validate
> `M > 0`. **`catch (InterruptedException) { interrupt(); }` must then `return`** — restore-and-continue
> spins. Safe *only* because savages decrement-only and there is **exactly one cook**; add a second and
> the refill must re-verify emptiness.
