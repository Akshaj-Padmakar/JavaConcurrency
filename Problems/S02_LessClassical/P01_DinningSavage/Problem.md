# Dining Savages

> Book ref: *The Little Book of Semaphores* §5.1.

> A tribe shares one pot that holds `M` servings. Any savage who's hungry helps themselves to one
> serving. A savage who finds the pot **empty** wakes the cook and waits. The cook refills the pot
> back to `M` and goes back to sleep. Write the synchronisation — and make sure the cook is woken
> **once per empty pot**, not once per hungry savage who noticed.

## What you're building

A **shared resource pool with batch replenishment**. Many consumers draw from a fixed-size pool one
item at a time; when it runs dry, a single producer refills the whole thing at once.

That's a connection pool that opens a batch of connections when exhausted, a slab allocator that
maps a fresh block, a token bucket that tops up, a prefetch buffer that refetches a page when
drained, a pool of preallocated upload buffers replenished by one allocator thread.

The property that makes this *not* a blocking queue: production is **"fill to `M`"**, not "add one".
One refill satisfies many waiting consumers at once, so waking a single one is wrong. And because
every consumer that arrives during the dry spell notices the pot is empty, the "please produce"
request has to be **collapsed to one** — otherwise you queue up N refills for one exhaustion.

Those two asymmetries — broadcast on the way back, coalesce on the way out — are the whole problem.

## The failures you're designing against

**1. Check-then-act on the count.** Two savages both see one serving left:

| # | Savage A | Savage B | `servings` |
|---|---|---|---|
| 1 | reads `servings` → 1, "not empty, I'll take one" | | 1 |
| 2 | | reads `servings` → 1, "not empty, I'll take one" | 1 |
| 3 | decrements | | 0 |
| 4 | | decrements | **−1** |

B ate a serving that never existed. The test and the decrement have to be one indivisible step.

**2. One empty pot, N refills.** Five savages arrive to find the pot empty. If each of them wakes the
cook, the cook cooks five times — four of those refills land on a pot that's already full, blowing
past `M` or discarding food, depending on how you wrote it. The pot was empty *once*; the cook should
work *once*.

## The API

```java
public class DinningSavage {
    public DinningSavage(int savageCount, int potCapacity);
    public void solve() throws InterruptedException;
}
```

```java
new DinningSavage(5, 2).solve();   // starts the cook and the savages, runs the table
```

That's the whole surface. The pot, the cook loop, and the savage loop are all internal — the caller
picks the two sizes and presses start. Everything the problem is about happens behind `solve()`.

## Constraints

- **No `BlockingQueue`, no `Semaphore` from `java.util.concurrent`.** The pot *is* the thing you're
  being asked to build, and the book's solution is written in semaphores — so if you want one,
  hand-roll it.
- `ReentrantLock` + `Condition` is fine. Be ready to drop to `synchronized` / `wait` / `notifyAll`.
- **No busy-waiting**, and no `Thread.sleep` standing in for synchronisation. Sleeping to simulate
  cooking or eating is fine.

## Requirements

- A savage never takes from an empty pot.
- `servings` is never negative and never exceeds `M`.
- The cook refills **only** when the pot is empty.
- **Exactly one refill per exhaustion event**, however many savages noticed it.
- After a refill, *every* waiting savage gets a chance to re-check — not just one.
- No lost wakeup if the cook finishes refilling before a savage starts waiting.
- No busy-waiting.
- Clean shutdown: no savage and no cook left parked on a condition.

## Edge cases

- `M = 1` — the pot empties on every single take.
- `M = 0`, or negative.
- `N = 1` savage.
- `N` much greater than `M` — most savages are waiting most of the time.
- Two savages taking the last two servings at the same instant.
- A savage that wakes the cook and is then interrupted before it eats.
- Shutdown requested while savages are parked and the cook is mid-refill.
- The cook is slower than the savages consume.

## Questions to answer before you code

1. The "is the pot empty?" test and the decrement must be one indivisible step. Write the
   interleaving that breaks if they aren't, and say what the resulting count is.
2. Several savages find the pot empty at nearly the same moment. How many times should the cook
   refill? What extra state do you need to make it exactly that many, and where must it live?
3. When the cook finishes refilling, does it wake **one** savage or **all** of them? Justify from
   what the waiting savages' predicate is, not from what feels efficient.
4. When a savage wakes the cook, `signal` or `signalAll`? Is your answer the same as Q3 — and if not,
   what's different about the two directions?
5. If the cook refills *before* any savage has begun waiting, is that wakeup lost? What property of
   your state decides the answer?
6. Whatever "the cook has already been asked" state you introduced — who clears it, the savage or the
   cook? Trace both choices; one of them can drop a refill.
7. Should the cook hold the lock while it's actually cooking? Name the cost of each answer.
8. Can one savage take serving after serving while another never eats? Does the spec forbid it, and
   what would you change to bound the wait?
9. This is one producer doing **batch** production and many consumers taking one at a time. Name the
   two ways that differs from a bounded blocking queue — and say which of your signalling choices is
   a direct consequence of the batching.
10. Trace `M = 1`, `M = 0`, and `N = 1`. Does any of them hang or spin?
11. `solve()` owns the threads, so a caller can only start the whole simulation. What would the
    component form look like — the two methods a caller's *own* threads would call — and which shape
    is a system coding round actually asking for?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "the pot" | a shared bounded resource pool |
| "fill it all the way back up" | batch replenishment |
| "test it and take it in one indivisible step" | atomic read-modify-write under the lock |
| "both savages saw one serving left" | check-then-act race, lost update |
| "wake the cook only once for one empty pot" | request coalescing / idempotent signalling |
| "wake everybody and let them sort it out" | broadcast — `signalAll` / `notifyAll` |
| "the cook finished before anyone was listening" | lost wakeup |
| "re-check the situation after waking" | guarded suspension, predicate loop |
| "one savage eats forever, another never does" | starvation |
| "don't hold the lock while doing the slow part" | keeping work out of the critical section |
| "nobody is left asleep when we shut down" | clean termination / draining waiters |
