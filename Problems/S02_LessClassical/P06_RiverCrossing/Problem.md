# River Crossing

> Book ref: _The Little Book of Semaphores_ §5.7.

## Problem

On one bank of a river, **hackers** and **serfs** (Linux users and Windows users) want to cross using
a boat that holds **exactly 4** people. To avoid a fight, the boat may only leave with a **valid
crew**:

- **4 hackers**, or
- **4 serfs**, or
- **2 hackers + 2 serfs**.

Any other mix (e.g. 3 hackers + 1 serf, or 1 + 2) is **not allowed** — that person must wait.

A person arrives, waits until they can board a valid group of 4, boards, then **one** of the four
"rows the boat" (a single designated action per trip). Everyone in a boat is on the same trip.

### API (typical)

```java
class RiverCrossing {
    void hacker(int id);  // wait for a valid crew, board, maybe row
    void serf(int id);    // wait for a valid crew, board, maybe row
}
```

### Requirements

- **Valid crew only** — every departing boat is `4H`, `4S`, or `2H+2S`. Never `3+1` or `1+3`.
- **Exactly 4 per boat** — no boat leaves with fewer or more.
- **Barrier** — all 4 board before the boat leaves; nobody boards the *next* boat mid-departure.
- **Exactly one rower** per boat (a single action performed once per trip).
- **No busy-waiting.**

## The trap

The valid combinations are only `{4H, 0S}`, `{0H, 4S}`, `{2H, 2S}`. So the "am I the one who
completes a boat?" logic is **count-dependent on both types**:

- If I'm a hacker and there are already **3 other hackers** waiting → I complete a `4H` boat.
- If I'm a hacker and there are **1 other hacker + 2 serfs** waiting → I complete a `2H+2S` boat.
- Otherwise I wait.

The person who completes the group must **release exactly the right 4** and reset the counters — and
must not accidentally admit a 5th or an invalid mix.

## Points to Ponder

- **Who is the "captain"?** The arriving person who *completes* a valid 4 is the one that dispatches
  the boat: they release the other 3 (or signal them), reset the waiting counts, and trigger the
  barrier. (The "last one in the group flips the switch" pattern again.)
- **How do you detect a valid crew on arrival?** After incrementing your type's count, check:
  `hackers == 4` → release 4 H; `serfs == 4` → release 4 S; `hackers >= 2 && serfs >= 2` → release
  2 H + 2 S. What order do you test these in, and does it matter?
- **The mutex + boarding gate.** You must hold a lock while inspecting/updating counts, but you must
  **not** let a 5th person mutate the counts mid-departure. How do you hold newcomers out until the
  current boat has left? (A boarding flag / turnstile, or release exactly-4 permits.)
- **The size-4 barrier.** All 4 must board together and the boat leaves as a unit; then it must
  **reset** for the next trip. `CyclicBarrier(4)`? A generation counter? Semaphore permits?
- **Exactly one rower.** How do you designate a single thread of the four to "row"? (The captain, or
  the last through the barrier via `CyclicBarrier`'s barrier-action.)
- **Which JDK primitives fit?** `Semaphore` (release exactly-4 boarding permits per type) +
  `CyclicBarrier(4)` (rendezvous + auto-reset + a barrier action for the rower). Compare with a
  hand-rolled monitor.
- **Starvation / fairness.** Can a lone serf wait forever while hackers keep arriving in 4s? Is that
  acceptable? Could you bias toward the mixed boat to reduce waiting?
- **Relation to Building H₂O.** Same family: a **typed barrier** with a composition constraint —
  here the composition has *three* valid shapes instead of one, which is what makes the "captain"
  logic trickier.
- **Termination.** Leftover people who can never form a valid boat (e.g. exactly 1 serf and 3
  hackers total) will wait forever — is that expected? How would you shut down cleanly?
