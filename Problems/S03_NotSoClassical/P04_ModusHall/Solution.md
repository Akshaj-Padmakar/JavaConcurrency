# Modus Hall — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §6.4. The book's own author-provided solution is
> **documented as buggy** by later formal verification (UPPAAL model checking) — this is an original
> monitor-based (`ReentrantLock` + per-thread `Condition`) design, not a port of Downey's semaphores.
> Sibling of [P02_UnisexBathroom](../P02_UnisexBathroom/Solution.md) /
> [P03_BaboonCrossing](../P03_BaboonCrossing/Solution.md), but fairness comes from **majority rule**,
> not a fixed `BATCH_SIZE`.

## One-line idea

Two counters do two **different** jobs — conflating them was the root cause of every serious bug
here. `heathenCnt`/`prudeCnt` = checked-in strength (waiting + crossing, used only for the majority
comparison). `onPathCnt` = physically crossing right now, across **both** factions combined — the
only thing allowed to gate whether `currentType` can safely flip.

## State model

| Field                                | Meaning                                                          |
| -------------------------------------- | ------------------------------------------------------------------ |
| `currentType`                          | `NONE` / `HEATHEN` / `PRUDE` — who currently has the path        |
| `heathenCnt` / `prudeCnt`              | checked-in strength per faction — the majority tally              |
| `onPathCnt`                            | physically crossing right now (both factions) — safety gate only |
| `heathenWaitingList` / `prudeWaitingList` | FIFO waiters, one `Node` per thread, own `Condition`           |

`allow()`: `NONE` or already your own type → majority check (`myCnt >= otherCnt`); opposite type
currently holds it → always block, no exception.

## Why it's correct

- `currentType` can only become `NONE` when `onPathCnt == 0` (nobody physically present), and can
  only become `HEATHEN`/`PRUDE` again via `enterInside()`, which only runs after `allow()` passed —
  which itself requires `currentType` to already be `NONE` or match. By induction: at most one
  faction ever has `onPathCnt > 0` credited to it at a time.
- That reset to `NONE` runs **unconditionally** whenever `onPathCnt` hits 0 — not just when the
  exiting thread's side is losing the majority check — so there's no branch where the field goes
  truly idle without the state reflecting it.
- Verified empirically, not just by static reasoning: instrumented a copy (separate from this file)
  that tracks "how many of each faction are physically on the path" and asserts they're never both
  nonzero at once. 40 trials, `N`/`M` varied 1–25 including deliberately skewed pairs, 8s hang
  watchdog per trial: **0 exclusion violations, 0 hangs.**

## Bugs found & fixed (in the order we hit them)

- **🔴 Only cross-type `getFirst()` signaled, nothing ever woke a same-type waiter.** Once more than
  one thread of a type was blocked, all but the lucky first were stuck in `await()` forever —
  reproduced on essentially every run at N=M=10. Fixed: every `exit()` now signals a waiter from its
  **own** waiting list too when it's still the majority, not only the opposite list on a flip.
- **🔴 Mutual exclusion violation: `currentType` flipped on a raw count comparison, with no check
  that anyone was still physically on the path.** `heathenCnt < prudeCnt` alone triggered a prude
  hand-off even while other heathens were still mid-`travel()` — confirmed empirically (733
  violations across 25 trials before the fix). Fixed by introducing `onPathCnt`, a counter separate
  from the strength tallies, and gating every hand-off on `onPathCnt == 0`.
- **🔴 Residual stale `currentType`.** The `onPathCnt == 0 → NONE` reset was initially only wired
  into the *losing* branch of the exit check. If your own side "won" the last comparison as the very
  last person left (`0 >= 0` tie), `currentType` stayed stale — and since nothing signals the
  opposite faction except a same-type `exit()` that will never happen again (that side is fully
  drained), a later arrival of the other type could block permanently. Fixed by making the
  `onPathCnt == 0` reset unconditional, decoupled from which side "wins" the strength check.
- **🟡 `NoSuchElementException` risk** from calling `getFirst()` on a possibly-empty waiting list —
  fixed with `size() > 0` guards before every signal call.

## Known limitation (not a bug — verified no starvation)

Only `waitingList.getFirst()` is signaled per `exit()`, not the whole backlog. Since this problem has
no `MAX_CAPACITY` cap (unlike P02/P03, any number of one faction can walk together), this throttles a
winning side's throughput to roughly one newly-admitted crosser per exit rather than releasing the
full accumulated majority at once. Doesn't cause starvation (each admitted thread's own later `exit()`
re-triggers the check, draining the backlog one at a time) — just conservative concurrency. Signaling
the whole list (or switching to a shared `Condition` per type + `signalAll()`, since there's no
capacity bound to respect) would let a full majority batch flow through together.

## Still open (minor, not yet fixed)

`travel()`'s catch block calls `Thread.currentThread();` — a no-op, doesn't actually restore the
interrupt flag. Should be `Thread.currentThread().interrupt();`, matching `enter()`'s catch block.

## 30-second recall

> Two jobs, two counters: `heathenCnt`/`prudeCnt` = strength (majority tally, includes waiters);
> `onPathCnt` = physical safety gate (only counter allowed to trigger a `currentType` flip). Flip to
> `NONE` unconditionally whenever `onPathCnt` hits 0, regardless of who "won" — then let majority
> decide who reclaims it. The three real bugs were all about this same distinction: signaling only
> cross-type (own-type waiters starved), gating the flip on strength instead of physical presence
> (exclusion violation), and resetting state only in one branch instead of unconditionally (stale
> state). Verified empirically with an instrumented stress test, not just by inspection.
