# Baboon Crossing — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §6.3.
> Same shape as [P02_UnisexBathroom](../P02_UnisexBathroom/Solution.md): mutual exclusion between
> two "sides" + no-starvation, plus a rope capacity cap.

## One-line idea

Track `currentDir`, `currentDirCnt` (admitted this batch, ≤ `BATCH_SIZE`), `onRopeCnt` (live, ≤
`MAX_CAPACITY`). Same-direction arrivals admitted freely under both caps. Switch direction only when
`onRopeCnt == 0` **and** (batch exhausted **or** own queue drained early) — always reset to `NONE`
first, then hand off to whichever side has waiters.

## State model

| Field                              | Meaning                                          |
| ----------------------------------- | ------------------------------------------------- |
| `currentDir`                        | `LEFT` / `RIGHT` / `NONE`                        |
| `currentDirCnt`                     | total admitted this batch (resets on turnover)   |
| `onRopeCnt`                         | live occupants (caps concurrency)                |
| `leftBabonWaitingList` / `right...` | FIFO queue, one `Node` per waiter, own `Condition` |

## Bugs found & fixed

- **🔴 Missing `onRopeCnt--` in `exit()`.** Count only ever grew → after `MAX_CAPACITY` total
  crossings, `onRopeCnt < MAX_CAPACITY` false forever, `onRopeCnt == 0` never true again → total,
  deterministic deadlock (not timing-dependent — reproduces every run). Fix: decrement at top of
  `exit()`, under the lock.
- **🔴 Queue removed by `poll()` (head) instead of by identity.** A batch hand-off wakes up to
  `MAX_CAPACITY` threads at once; non-fair `ReentrantLock` doesn't reacquire in signal order, so
  `poll()` could delete a *different* thread's still-waiting `Node` → that thread orphaned in
  `await()` forever. Fix: `currentWaitingList.remove(this.node)` (identity-based).
- **🔴 `currentDir` left stale after a turnover with empty queues.** Old code only reset the count,
  not the direction, on a same-direction restart — a late arrival from the *other* side could then
  wait on a state nothing would ever re-check. Fix: `signalOppositeDirBabon()` /
  `signalCurDirBabon()` now both call `resetRope()` (→ `NONE`) **unconditionally**, before deciding
  who (if anyone) to wake.
- **🟡 Interrupt swallowed without restoring the flag** in both catch blocks — fixed
  (`Thread.currentThread().interrupt()` added).

## Known latent gap (not yet hit by `solve()`)

- If a thread is interrupted **while parked in `await()`**, `enter()` swallows the exception and
  `run()` still calls `travel()` → `exit()` — but `enterInside()` never ran, so `exit()`'s unconditional
  `onRopeCnt--` decrements a count that was never incremented for this thread → `onRopeCnt` goes
  negative → capacity cap silently disabled for the rest of the run. Same root cause also leaves the
  thread's `Node` stuck in the waiting queue forever. Not exercised today (nothing calls
  `.interrupt()` in `solve()`), but real. Fix: only run `travel()`/`exit()` if `enter()` actually
  succeeded (track an `entered` flag).

## Sizing `BATCH_SIZE` / `MAX_CAPACITY` (secondary — reference, not the main point)

Let `C = MAX_CAPACITY`, `B = BATCH_SIZE`, `T` = per-crossing duration (use the **max**, not average,
for a hard bound).

- **Tradeoff:** worst-case wait for the waiting side ≈ `ceil(B / C) * T`. Throughput loss ≈ one
  switch-tax (`~T`) per `min(B, side-count)` crossings. Bigger `B` → better throughput, worse worst
  case. Bigger `C` helps both — cap it only if something external forces you to.
- **Finite-N/M nuance:** the code's early-drain branch (own queue empties before hitting `B`) means
  the *realized* worst wait is `ceil(min(B, remaining count of the crossing side) / C) * T` — `B`
  only really bites when **both** sides have demand ≫ `B`; with a skewed/small side it's a safety net
  you rarely hit.
- **Case A — `C` given (fixed/physical):** pick `B ≈ floor(W_max * C / T)` for a target wait bound
  `W_max`; no explicit bound → default **`B ∈ [2C, 4C]`**. `B = C` is the fairest sane floor (below
  that you can't even fill the rope once per batch).
- **Case B — `C` also free:** if nothing external forces a cap, the classic book problem has *none*
  — set `C` unbounded (e.g. `max(N, M)`) and `B` stops mattering much (a whole batch enters near
  instantly, worst wait ≈ `T` regardless of `B`). Impose `C` only for a real reason (resource limit,
  or — as here — deliberately moderate `C` to force simultaneous multi-thread wake-ups and exercise
  the races above; `C = 1` would never have surfaced them).

## 30-second recall

> `currentDir` + `currentDirCnt` (batch) + `onRopeCnt` (live), one lock, one `Condition` per waiter.
> Switch only when rope's empty; always reset to `NONE` first. Three historical bugs: forgot to
> decrement `onRopeCnt` on exit (hard deadlock), removed the wrong queue entry under concurrent
> wake-ups (self-remove by identity), left `currentDir` stale on an empty-queue turnover (always
> reset first). `B` vs `C`: `B` bounds fairness, `C` bounds concurrency — bigger `C` is free lunch,
> bigger `B` trades fairness for throughput.
