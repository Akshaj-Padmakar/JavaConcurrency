# The Roller Coaster

> Book ref: _The Little Book of Semaphores_ §5.8.

## Problem

`N` **passenger** threads and one **car** that holds exactly `C` passengers (`C < N`). The car runs
around a track repeatedly. The rules:

- The car **waits** at the platform until it is **full** (`C` passengers boarded), then departs.
- A passenger **waits** to board; boards only when the car is at the platform with room.
- The car cannot depart until it is **full**; passengers cannot board a car that is **running** or
  already full.
- When the ride finishes, all `C` passengers **unboard**, and only then may the next `C` board.

So each passenger: `board → ride → unboard`. The car: `load C → run → unload C → repeat`.

### API (typical)

```java
class RollerCoaster {
    void passenger(int id);   // wait for a seat, board, ride, unboard
    void car();               // wait until full, depart, run, arrive, release passengers
}
```

### Requirements

- **Exact capacity** — the car departs with *exactly* `C` passengers, never more or fewer.
- **Board/unboard rendezvous** — a passenger doesn't "ride" until the car has departed with them
  aboard; the car doesn't depart until all `C` have boarded; the next group can't board until the
  previous group has fully unboarded.
- **No busy-waiting.**

## Part 2 (the classic extension): multiple cars

Now there are `M` cars, but the **track allows only one car at the platform at a time** (boarding is
serialized), and only one car running is fine. This adds:

- **Mutual exclusion at the platform** — cars must `load`/`unload` one at a time, in order.
- Track/platform ordering so cars don't overtake at the station.

## The traps

1. **Two barriers per ride.** Boarding is a barrier of size `C` (car waits for `C`, passengers wait
   for departure); unboarding is another (car waits for all `C` off, passengers wait for arrival).
   Both must **reset** cleanly for the next ride — a straggler from ride *k* must not leak into ride
   *k+1*.
2. **Exact-`C` gate.** While a car is loading, passenger `C+1` must **wait**, not squeeze in; while
   the car is running, no one boards. (The "admit exactly `C`, then close the gate" pattern.)
3. **The car is the coordinator.** Only the car signals "board" and "unboard"; passengers signal the
   car when the seat count is reached.

## Points to Ponder

- **Which barriers, and how to reset them?** Boarding barrier + unboarding barrier. `CyclicBarrier`?
  Two `Semaphore`s (`allAboard`/`allAshore`)? A generation counter? What re-arms them per ride?
- **Who counts to `C`?** The passenger that boards last (`boarded == C`) signals the car to depart —
  "last one flips the switch," like Santa's 9th reindeer / H₂O's completer.
- **Exact-`C` admission.** How do you hold passenger `C+1` out until the current ride is done? (A
  boarding gate flag / turnstile, or release exactly `C` boarding permits — the same "one cohort in
  flight" invariant from River Crossing / H₂O.)
- **Board vs unboard ordering.** The book insists boarding and unboarding are distinct rendezvous:
  everyone must be aboard before `run()`, and everyone off before the next `load()`. Why can't you
  collapse them into one?
- **Multiple cars (Part 2).** How do you serialize `load`/`unload` so only one car is at the platform,
  and keep cars in order? (A car mutex, or a queue of cars.)
- **Which JDK primitives fit?** `CyclicBarrier(C+1)` (C passengers + the car rendezvous), or
  `Semaphore`s for the two hand-offs, plus a mutex for multi-car. Compare with a hand-rolled monitor.
- **Relation to earlier problems.** This is a **reusable barrier of size `C`** (like H₂O/River, but
  untyped) with a **coordinator** (like Barbershop/Santa) and **exact-capacity admission**.
- **Termination.** With `N` passengers and rides of `C`, leftover `N % C` passengers can never fill a
  car — do they wait forever? How would you shut down cleanly?
