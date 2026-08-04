# Sushi Bar — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §7.1. The book's own **broken** reference solution is
> the whole point of this problem — see [Problem.md](Problem.md#the-trap-this-is-the-famous-part).
> This repo's solution is monitor-based (`ReentrantLock` + one `Condition` per waiting customer).

## One-line idea

Strict FIFO gating, not just a wake-up signal, is what defeats the book's trap. A customer may only
proceed once **two** things are true: the bar isn't full/partying, **and** they are literally the
head of the waiting queue (`waitingList.peek() == this.node`). Signal order becomes irrelevant —
only queue position governs who's allowed through.

## State model

| Field         | Meaning                                                          |
| -------------- | ------------------------------------------------------------------ |
| `insideCnt`    | currently seated (≤ `MAX_CAPACITY` = 5)                          |
| `partying`     | true once `insideCnt` hits 5 — blocks **everyone**, including the queue head, until the whole party clears |
| `waitingList`  | FIFO queue, one `Node` per waiter, own `Condition`                |

## Why it's correct — and why the book's non-solution isn't

The book's broken solution lets a woken customer race an unrelated new arrival for the mutex, and
whoever wins gets seated — which can seat more than 5 people. Here, `insideCnt` is checked-and-
incremented atomically under the same lock (no separate release-then-reacquire gap), so capacity
can't be raced past. And even if a brand-new customer's `lock()` call barges ahead of the whole
queue (legal under `ReentrantLock`'s non-fair default), its own `peek() != this.node` check fails —
it isn't the head — so it steps aside and re-parks. Queue position, not signal delivery, decides who
proceeds.

`partying` blocks even the queue head until `insideCnt` hits exactly 0, so a full bar really does
make new arrivals wait for the **entire** party to leave, not just for one seat — matches the
problem statement.

Verified empirically (separate instrumented copy, source untouched): 30 trials, N scaled 5→92,
asserting `insideCnt` never exceeds 5 — **0 violations, 0 hangs.**

## A race that looks scary but isn't (worth internalizing)

When a party ends, `exit()` signals up to 5 queued customers (T1..T5, in queue order) in one loop.
Intuition might say: what if T2's thread wins the lock before T1's does, finds `peek() != T2`, and
re-parks — does it ever get signaled again, or is it now stuck?

It's stuck **if** that happens — but it can't happen, here specifically. Each `signal()` call
transfers that thread into the **same `ReentrantLock`'s internal AQS wait queue**, in the exact order
`signal()` was called. Once inside that queue, threads are granted the lock in strict FIFO order
**relative to each other**, regardless of the lock's fair/non-fair setting — non-fairness only lets a
**brand-new**, not-yet-enqueued caller barge past the whole queue; it does not let T2 skip ahead of
T1 once both are already enqueued. So T1 is guaranteed to run first, succeed, and `poll()` itself off
before T2 ever gets a turn — by the time T2 runs, it correctly finds itself at `peek()`.

Net: the `peek()` check's real job is defending against a **new, unrelated arrival** barging the
whole queue (the book's actual bug) — not against reordering within an already-signaled batch, which
`ReentrantLock` rules out by construction. Worth keeping the check anyway: it's what makes
correctness independent of that AQS implementation detail rather than silently relying on it.

## Bugs found & fixed

- **🔴 Interrupted-while-waiting corrupted the queue.** If `await()` threw `InterruptedException`,
  the node was never removed from `waitingList` — and because correctness here depends on strict
  `peek() == this.node` ordering (unlike Bathroom/Baboon, where a stray dead node just wasted one
  batch-signal slot), a dead node stuck at the **head** would permanently block every customer behind
  it. Fixed: on interrupt, remove the node by identity (`waitingList.remove(this.node)`, not
  `poll()`, since the interrupted thread may not be at the head) and explicitly signal the new head
  (if any) — removing a node doesn't itself wake anyone to re-check.
- **🔴 `run()` didn't check whether `enter()` actually succeeded.** If a customer was interrupted
  while waiting (never got seated), `run()` still unconditionally called `dineIn()` then `exit()` —
  `exit()`'s `insideCnt--` decremented a counter that thread never incremented, corrupting `insideCnt`
  for everyone else (could even let more than 5 in, since the count would read artificially low).
  Fixed: `enter()` now returns `boolean`, and `run()` does `if (!enter()) return;` — skips
  `dineIn()`/`exit()` entirely on a failed entry. Same latent-gap family as the queue-corruption bug
  above, closed the same way.

## Still open

- **Minor redundancy:** `insideCnt == MAX_CAPACITY` in the `while` guard adds nothing beyond what
  `partying` already covers — both are set together, atomically, in the same critical section
  (`if (insideCnt == MAX_CAPACITY) partying = true;`), so `insideCnt` can never be seen at capacity
  with `partying` still false. Harmless, just dead weight.

## 30-second recall

> Two-part gate: `partying` (blocks everyone until the whole party of 5 clears) + `peek() ==
> this.node` (strict FIFO — defeats the book's "new arrival races a woken customer for the mutex"
> bug by making queue position, not signal order, the deciding factor). `ReentrantLock` itself
> guarantees threads transferred via `signal()` stay in relative FIFO order, so a signaled batch
> never reorders itself — the `peek()` check exists for new arrivals barging the *whole* queue, not
> for that. Two related interrupt-handling bugs, both fixed: queue cleanup on interrupt (remove by
> identity + wake new head) and `run()` calling `exit()` after a failed `enter()` (now guarded by
> `enter()` returning `boolean`).
