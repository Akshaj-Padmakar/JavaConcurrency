# The Sushi Bar Problem

> Book ref: _The Little Book of Semaphores_ §7.1. First problem of Chapter 7 ("Not remotely
> classical problems"). Adapted from a problem by Kenneth Reek.

## Setup

- A sushi bar has 5 seats.
- Arrive while a seat is free → sit down immediately.
- Arrive when all 5 are full → that's a "party," and they're dining **together**. You must wait for
  **the entire party to leave** before anyone new sits down (not just for one seat to free up).

## The trap (this is the famous part)

The book gives its own **broken** solution specifically to illustrate the trap — worth knowing
because it's the natural first instinct:

- A customer arriving to a full bar releases the mutex and blocks on a separate semaphore.
- When the last diner of a party leaves, it signals that semaphore to wake the waiters and clears
  the "must wait" flag.
- **The bug:** woken customers don't get the mutex back automatically — they have to *compete* for
  it against brand-new arrivals. If a fresh arrival wins that race and grabs a seat before an already
  woken (higher-priority) customer does, you can end up with **more than 5 people seated at once** —
  not just unfair, an actual violation of the core constraint.

So: releasing a lock while waiting, then re-acquiring it later, is not by itself enough — you also
need to guarantee **who** gets to act first once the lock is available again.

## Key idea

- Track how many are currently seated and whether the bar is presently "full and partying."
- A newly arriving customer must be blocked by **two independent conditions**: (a) the bar isn't
  full, and (b) — the part the non-solution misses — **no one who arrived before them is still
  waiting to be let in**. Getting the wake-up order right is the actual puzzle here, not just
  counting seats.

## Ponder

- Why isn't "wake everyone when the party ends" by itself enough to guarantee correctness? What
  additional guarantee do you need about *who* proceeds first among the woken + newly-arrived?
- The book's hint says its solutions use **no additional variables** beyond `eating`, `waiting`,
  `mutex`, `block`, `must_wait`. If you solve this with a monitor (`Lock` + `Condition`) instead of
  raw semaphores, do you still need a similar minimal set, or does a `Condition`-per-thread queue
  sidestep the whole "wake order" problem differently?
- Does "wait for the entire party to leave" mean the 6th arrival waits for all 5, or could you let
  people trickle in as seats free up mid-party? (The problem statement is explicit — check it.)
- What's the throughput cost of "must wait for the whole party," compared to a design that let new
  arrivals fill seats as they open up? Is the batching here closer to Baboon Crossing's
  `MAX_CAPACITY`, or fundamentally different?
- Two different correct solutions exist in the book, neither needing new variables — what are the
  two structurally different ways to fix the race in the non-solution above?
