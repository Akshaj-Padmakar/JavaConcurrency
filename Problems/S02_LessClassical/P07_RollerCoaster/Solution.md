# Roller Coaster — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §5.8.

## One-line idea

One car of capacity `C`, `N` riders (`C < N`). Car repeats `load C → run → unload C`; each rider does
`board → ride → unboard`. It's a **reusable barrier of size `C`** (untyped) with a **coordinator**
(the car) and **exact-`C` admission** — two rendezvous per ride, both re-armed each ride.

## The two rendezvous (this is the crux)

| Rendezvous | Car waits for… | Riders wait for… |
| ---------- | -------------- | ---------------- |
| **Boarding**   | `C` riders aboard (`boarded == C`) | the car to **depart** |
| **Unboarding** | all `C` off (`onBoard == 0`)       | the car to **arrive** |

Why two, not one: the car must **not** start the next `load` until everyone from this ride is *off*,
or ride `k`'s stragglers leak into ride `k+1`. (Same cohort-mixing hazard as River Crossing — the
second barrier prevents it.)

## Core template (single-counter monitor)

```java
// Car
boarded = 0; onBoard = 0; loading = true; boardingOpen.signalAll();
while (boarded < C) carFull.await();     // BARRIER 1: wait for a full car
loading = false;                          // close the gate
runRide();                                // slow work OUTSIDE the lock
rideId++; running.signalAll();            // release riders (generation bump)
while (onBoard != 0) allAshore.await();   // BARRIER 2: wait until all off

// Rider
while (!loading || boarded == C) boardingOpen.await();  // EXACT-C GATE (only while loading!)
boarded++; onBoard++; int myRide = rideId;
if (boarded == C) carFull.signal();       // last boarder -> depart
while (myRide == rideId) running.await();  // ride MY generation
onBoard--;
if (onBoard == 0) allAshore.signal();     // last one off -> car may load next
```

## ⭐ Lesson 1: ONE source of truth per invariant (the bug this repo hit)

The original `Ez` version tracked "how many aboard" **twice** — `availableSeats` (decremented per
rider) **and** `riderOnBoard` (set in bulk by a `tryBoarding()` that did `riderOnBoard += CAPACITY`).
They drifted: `tryBoarding` only fired for **ride 0**, so from ride 1 on `riderOnBoard` stayed `0`.
The car's `while (riderOnBoard != 0)` then exited immediately → it reset seats **mid-ride** → **10
riders boarded one car** (capacity 5) → `riderOnBoard` went negative → **deadlock**.

**Fix:** count each boarder **once, in one place** (`riderOnBoard++` as they board; delete the bulk
`tryBoarding`). Verified: clean rides of exactly `C`.

> Two counters for the same fact **will** drift. One invariant → one variable.

## ⭐ Lesson 2: the exact-`C` gate must know the PHASE, not just the count

`while (riderOnBoard == CAPACITY)` admits anyone whenever `< CAPACITY` — **including mid-unboard**.
Normally nothing signals boarders during unboarding, but a **spurious wakeup** at `onBoard == 3`
would let a fresh rider board a half-empty, still-unloading car. Gate on a **phase flag** instead:

```java
while (!loading || boarded == C) boardingOpen.await();   // board ONLY while the car is loading
```

`loading` is true only between "car arrives empty" and "car full" — so boarding can't race unboarding.

## ⭐ Lesson 3: generation, not on/off flags, for the ride wait

Waiting on a boolean the car flips on **and** off (`while (!reached)`) is fragile — it works here only
because the car waits for *all* unboards before resetting `reached`. A **generation** id is robust:

```java
int myRide = rideId;                 // captured at boarding
while (myRide == rideId) running.await();   // wait for exactly MY ride to end
```

`rideId` only increments (never resets), so a straggler can never miss or double-count its ride.
(Same pattern as H₂O / River generation barriers.)

## Signal choices

- `carFull`, `allAshore`: only the **car** waits → `signal()` (one waiter).
- `boardingOpen`, `running`: **many** riders wait → `signalAll()`.
- Cut the slow `runRide()` **outside** the lock.

## Part 2: multiple cars

`M` cars, but only **one at the platform** at a time (boarding/unboarding is serialized; running in
parallel is fine). Add:

- A **car mutex / turnstile** so only one car `load`s or `unload`s at once.
- Optional **FIFO order** so cars don't overtake at the station (a queue of cars).

Each car still runs the same two-barrier ride internally; the extra layer just serializes platform
access.

## Use the JDK — library version

`CyclicBarrier(C + 1)` is a clean fit: the `C` riders **and** the car rendezvous at boarding, and
again at unboarding. The barrier **auto-resets**, and its **barrier action** is a tidy place for
"car departs" / "car arrives":

```java
CyclicBarrier boardBarrier   = new CyclicBarrier(C + 1, () -> System.out.println("Car departs"));
CyclicBarrier unboardBarrier = new CyclicBarrier(C + 1, () -> System.out.println("Car arrives"));
// rider: seat = boardSem.acquire(); boardBarrier.await(); ride; unboardBarrier.await(); boardSem.release();
// car:   boardBarrier.await(); run(); unboardBarrier.await();
```

Plus a `Semaphore(C)` to cap seats. `Phaser` is the flexible alternative. `CountDownLatch` is
one-shot → wrong tool for repeated rides.

## Termination (interview framing)

Infinite car loop + finite riders ⇒ `solve()` hangs on the last car wait (it wants a `C+1`-th carful
that never comes). This is the **"infinite coordinator vs finite arrivals"** case — a waiting car is
correct, not stuck. To terminate cleanly: a `done` flag + wake-all, or a `ridersRemaining` count so
the car exits at 0. Also note the `N % C` leftover: `N` not a multiple of `C` leaves `< C` riders who
can never fill a car — they *should* wait (blocking is correct), or you shut them down.

## Things to keep in mind

- **Two barriers per ride** (board + unboard), **both re-armed**.
- **One counter** for "how many aboard" (single source of truth).
- **Gate on the loading phase**, not just the count.
- **Generation** (`rideId`) for the ride wait, not an on/off flag.
- `signal()` for car-only conditions, `signalAll()` for rider crowds; ride outside the lock.

## 30-second recall

> Car of `C`, riders `board→ride→unboard`. **Two rendezvous** (board barrier + unboard barrier), both
> reset each ride. **One counter** for seats (two drift → capacity bug + deadlock). **Exact-`C` gate
> keyed on a `loading` phase** (not just count). **Generation `rideId`** for the ride wait. Last
> boarder signals depart; last un-boarder signals next load. Library: `CyclicBarrier(C+1)` + seat
> `Semaphore`. Part 2 = one car at the platform via a car mutex.
