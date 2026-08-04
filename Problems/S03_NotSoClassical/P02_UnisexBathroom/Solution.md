# Unisex Bathroom — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §6.2.
> This repo's `UnisexBathroom.java` is **monitor-based** (`ReentrantLock` + one `Condition` **per
> waiting thread**), and solves a **generalized** version of the book problem: same-gender mutual
> exclusion + no-perpetual-blocking, **plus** a concurrency cap (`MAX_CAPACITY`) and a batch size
> (`BATCH_SIZE`) that the classic problem doesn't have.

## One-line idea

Track `currentGender` + how many of that gender have been admitted (`currentGenderCnt`) and how many
are inside right now (`insideCnt`). Same-gender arrivals are admitted freely up to `BATCH_SIZE`
total / `MAX_CAPACITY` concurrent. Once the batch is exhausted **and** the room drains to empty,
force a turnover: hand off to the opposite gender if anyone is waiting there, otherwise start a fresh
batch for the same gender. The batch cap is what guarantees no-perpetual-blocking — it's a hard upper
bound on how long the other gender can be locked out.

## State model

| Field                          | Meaning                                                        |
| ------------------------------- | --------------------------------------------------------------- |
| `currentGender`                 | `MEN` / `WOMEN` / `NONE` (idle) — whoever "owns" the bathroom   |
| `currentGenderCnt`               | total admitted in the **current batch** (resets on turnover)     |
| `insideCnt`                      | occupants **right now** (drives the `MAX_CAPACITY` cap)         |
| `menWaitingList` / `womenWaitingList` | FIFO queue of `Node`s (one per waiting thread, own `Condition`) |

Each `Node` owns a **private `Condition`**, so a `signal()` targets exactly one thread — no
`signalAll()` thundering herd, and no ambiguity about which waiter woke.

## Admission rule (`allow()`)

```java
private boolean allow() {
    if (currentGender == TYPE.NONE) return true;
    return this.node.getType() == currentGender
        && currentGenderCnt < BATCH_SIZE
        && insideCnt < MAX_CAPACITY;
}
```

Guarded by `while (!allow()) await()` in `enter()` — the standard monitor pattern, so a signal that
arrives before the waiter re-enters `await()` (a "lost" wakeup) is harmless; the predicate is
re-checked, not assumed.

## Turnover logic (`exit()`)

```java
if (currentGenderCnt == BATCH_SIZE) {
    if (insideCnt == 0) {                       // batch AND room both drained
        if (getOppositeGenderQueue().size() > 0) signalOtherGenderToEnter();
        else                                      signalCurrentGenderToEnter();
    }
    // else: some of the batch are still inside — do nothing, wait for them to exit too
} else {
    if (getGenderQueue().size() > 0) getGenderQueue().peek().getCondition().signal();
    else if (insideCnt == 0)          signalOtherGenderToEnter();  // own queue empty early -> hand off
}
```

Two distinct hand-off triggers, both requiring `insideCnt == 0` (never switch gender while someone's
still inside):
1. **Batch exhausted** (`currentGenderCnt == BATCH_SIZE`).
2. **Own queue drains early**, before the batch cap is even hit — no reason to make the other gender
   wait for a batch that has nobody left to fill it.

Both call `signalOtherGenderToEnter()` → `resetBathroom()` (back to `NONE`) then wake up to
`MAX_CAPACITY` waiters from the opposite queue. If nobody is waiting there, `signalCurrentGenderToEnter()`
does the same reset and re-wakes the same gender's own queue instead — so a turnover **always**
re-evaluates from a clean `NONE` state, whether or not anyone was actually waiting.

## Why it's correct

- **Mutual exclusion across genders** — `allow()` only ever returns true for `currentGender == NONE`
  or `type == currentGender`; nothing lets both genders hold nonzero `insideCnt` at once.
- **No perpetual blocking** — `BATCH_SIZE` is a hard cap on one gender's uninterrupted run. However
  long the opposite queue waits, it's bounded by `MAX_CAPACITY` occupants × the time to admit
  `BATCH_SIZE` people, not "forever."
- **No lost wakeups** — every `await()` is behind a `while (!predicate)`, and predicate mutation
  always happens under the same lock as the check.
- **Every hand-off resets to `NONE` first** — this is what makes the "own queue empty" and "opposite
  queue empty" cases symmetric instead of special-cased.

## Bugs found & fixed

Both were timing-dependent — needed either simultaneous multi-thread wakeups (`MAX_CAPACITY > 1`) or
exact queue-empty timing at a turnover — so they didn't show up in casual single-thread-at-a-time
runs.

### 🔴 Fixed — wrong node removed from the queue on entry

```java
// before
enterInside();
waitingList.poll();                 // always removes the HEAD, not necessarily "this" thread

// after
enterInside();
waitingList.remove(this.node);      // removes THIS thread's node, wherever it sits
```
A batch hand-off signals up to `MAX_CAPACITY` nodes at once; because `ReentrantLock` is non-fair,
they don't necessarily re-acquire the lock in signal order. If a later-signalled thread won the lock
race and called `poll()`, it silently deleted an *earlier* thread's still-waiting `Node` from the
queue. That thread was never removed from `await()` by anything else afterward → **permanent
deadlock** for that one thread.

### 🔴 Fixed — stale `currentGender` after a same-gender restart

```java
// before
private void signalCurrentGenderToEnter() {
    currentGenderCnt = 0;                     // currentGender left as-is!
    ...
}

// after
private void signalCurrentGenderToEnter() {
    resetBathroom();                          // currentGender = NONE, currentGenderCnt = 0
    ...
}
```
If a batch finished (`insideCnt == 0`) while **both** queues happened to be momentarily empty,
`currentGender` was left pointing at the just-finished gender instead of `NONE`. An opposite-gender
thread arriving right after saw `currentGender != NONE && type != currentGender` → queued and waited
— with no future event scheduled to ever re-check the turnover, since nothing else runs until someone
of the *stale* gender shows up. With the finite `N`/`M` threads in `solve()`, this was a real
(timing-dependent) deadlock, not just theoretical.

## Follow-up questions (and answers)

**Q: Why batching instead of strict FIFO or a turnstile?**
FIFO/turnstile give perfect fairness but kill throughput — every switch is at most one person, so
same-gender parallelism (multiple people using the bathroom at once) is wasted. Batching keeps
`MAX_CAPACITY`-wide parallelism within a gender while still bounding how long the other gender waits.
It's a throughput/fairness dial: `BATCH_SIZE` down → more fair, less throughput; up → the reverse.

**Q: What happens if `MAX_CAPACITY = 1`?**
Degenerates to one-at-a-time occupancy but keeps batching, i.e. up to `BATCH_SIZE` same-gender people
enter **sequentially** before a forced turnover check. Still correct — `MAX_CAPACITY` and
`BATCH_SIZE` are independent knobs, one bounds concurrency, the other bounds total-before-turnover.

**Q: What happens if `BATCH_SIZE = 1`?**
Turnover is checked after every single admission — closest to a turnstile. Correct, just low
throughput (no gain from `MAX_CAPACITY > 1` since the batch cap kills same-gender parallelism after
the first person).

**Q: Why one `Condition` per thread instead of two shared conditions (one per gender)?**
Two shared conditions + `signalAll()` would work, but every waiter wakes on every signal and most
just re-check `allow()` and go back to sleep — wasted wakeups under load. A private condition per
node lets `signal()` target exactly the threads you intend to admit (`peekFirstK`), at the cost of
one `Condition` object per waiter (cheap) and needing to track *which* nodes to signal explicitly
(the `peekFirstK` list) rather than "just wake everyone."

**Q: Why does the entering thread remove itself from the queue instead of the signaller removing it?**
Because a signal only means "you're *eligible* to check again," not "you're admitted" — the woken
thread still re-validates `allow()` after reacquiring the lock (it could lose a race to
`MAX_CAPACITY` against other newly-signalled threads... actually it can't, since `enterInside()`
happens under the same lock hold that the `while` loop exits with, but the general principle holds:
never assume you'll get in just because you were signalled). Self-removal on actual, confirmed entry
avoids the head-of-queue assumption that caused the first bug above.

**Q: Could two people of different genders ever both see `allow() == true` at the same instant?**
No — `allow()` is only ever evaluated while holding `lock`, and every mutation of `currentGender`
/ `currentGenderCnt` / `insideCnt` also happens under the same lock. There's no gap between "check"
and "act" (`enterInside()` runs inside the same critical section as the `allow()` check that gated
the loop exit).

**Q: Is this fair *within* a gender (does FIFO order hold for, say, men vs men)?**
Mostly — `peekFirstK` signals the first K entries in queue-insertion order, and `LinkedList` as a
`Queue` preserves insertion order, so batch hand-offs prefer the longest-waiting same-gender threads.
But `ReentrantLock` itself is non-fair, so lock **acquisition** order among threads racing for the
lock isn't guaranteed FIFO — a "fair" logical order can still be served out of order at the OS
scheduling level. Use `new ReentrantLock(true)` if strict fairness matters more than throughput.

**Q: How would you generalize this beyond 2 genders (K categories)?**
Replace `currentGender`/`TYPE` with a generic category key, and `menWaitingList`/`womenWaitingList`
with a `Map<Category, Queue<Node>>`. Turnover logic becomes "pick the next category with a non-empty
queue" (round-robin, or longest-waiting-queue-first) instead of the hardcoded men/women binary choice
in `signalOtherGenderToEnter`.

**Q: What if the bathroom needs to run forever (no fixed `N`/`M`, threads keep arriving)?**
The core logic doesn't change — `solve()`'s `join()`-based shutdown does. You'd need an explicit
`stop` flag checked in the `while (!allow())` loop's predicate (`while (!stop && !allow())`) and
broadcast it via `signal()`/loop-through-and-signal on **every** queued node at shutdown, or blocked
threads never wake. (Same "flag + wake every waiter" rule as the other problems in this repo.)

**Q: How would you unit-test the two fixed bugs so they can't regress?**
Force the race deterministically instead of relying on timing luck: set `MAX_CAPACITY` (via a
constructor param — currently hardcoded, worth changing for testability) to something ≥ 2, start
several same-gender threads that each block on a `CountDownLatch` right after `enterInside()` before
returning (so they don't exit immediately and trigger real interleavings), and assert every started
thread eventually completes (`join()` with a timeout) rather than hanging. A hung `join()` is exactly
the symptom both original bugs produced.

**Q: How does this compare to the book's actual semaphore solution?**
The book's canonical answer uses a `mutex` to guard a shared reader-style counter, plus per-gender
"turnstile" semaphores, and doesn't include a concurrency cap — any number of one gender may be
inside simultaneously, the only constraint is the men/women exclusion and a bounded-wait guarantee
(usually via "empty the queue you found, don't keep re-checking" logic, similar in spirit to this
repo's batching). The monitor version here trades the classic semaphore counting trick for explicit
counters + a `while`-guarded predicate, which is more verbose but arguably easier to reason about
than raw semaphore arithmetic.

## 30-second recall

> `currentGender` + `currentGenderCnt` (batch total) + `insideCnt` (live occupancy), one lock, one
> `Condition` per waiter. `allow()`: `NONE`, or same gender under both caps. Turnover (`insideCnt==0`
> AND (batch done OR own queue empty)) always resets to `NONE` first, then wakes up to
> `MAX_CAPACITY` from whichever queue should go next (opposite preferred, same gender otherwise).
> Two historical bugs, both timing-dependent under multi-thread hand-offs: (1) self-removal by
> **identity** (`remove(node)`), not `poll()`, since signalled threads don't reacquire the lock in
> signal order; (2) **always** reset `currentGender` to `NONE` on turnover, even when restarting the
> same gender, or a late opposite-gender arrival can wait on a state nothing will ever re-check.
