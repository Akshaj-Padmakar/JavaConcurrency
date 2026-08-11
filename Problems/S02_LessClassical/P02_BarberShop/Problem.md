# The Barbershop

> Book ref: *The Little Book of Semaphores* §5.2.

> A barbershop has one barber, one barber's chair, and a waiting room with `N` chairs. With no
> customers the barber sits down and sleeps. A customer who arrives wakes him if he's asleep, sits
> and waits if he's busy and a chair is free, and **leaves** if the waiting room is full. Write the
> synchronisation — and make sure the barber and the customer agree on when the haircut is actually
> happening.

## What you're building

A **bounded work queue with load shedding and a synchronous hand-off**. That's a request handler with
a fixed-size accept backlog: when the backlog is full you don't block the caller and you don't grow
the queue — you reject immediately (the balk, i.e. a 503), because a caller queued behind a
ten-second backlog is worse than a caller told "no" now.

Two things make it *not* a blocking queue, and both are the actual exercise:

1. **The producer never blocks.** A full waiting room turns a customer away instead of parking it.
   That's `offer()` semantics, not `put()` — deliberate load shedding.
2. **The hand-off is two-way.** A queue is fire-and-forget: the producer drops an item and walks off.
   Here the customer must stay until **its own** haircut is finished, and the barber must not start
   the next customer until the current one has actually left the chair. That's a synchronous
   request/response — each caller blocks on *its own* completion, not on "some work finished".

Point 2 is why one shared "done" flag isn't enough, and it's the part people under-build.

## Worked example

`N = 2` waiting chairs, plus the barber's chair.

| # | Actor | Situation | Waiting room | Barber | Outcome |
|---|---|---|---|---|---|
| 1 | — | shop empty | 0 | asleep | — |
| 2 | C1 | arrives, barber asleep | 0 | wakes | C1 takes the chair |
| 3 | C2 | arrives, barber busy, 2 free | 1 | cutting C1 | sits and waits |
| 4 | C3 | arrives, barber busy, 1 free | 2 | cutting C1 | sits and waits |
| 5 | C4 | arrives, **room full** | 2 | cutting C1 | **balks — leaves** |
| 6 | barber | finishes C1 | 1 | cutting C2 | C1 leaves, C2 promoted |

Every one of the three arrival paths appears: wake the barber, sit down, or leave.

## The failures you're designing against

**1. Racing into the last chair.** Two customers both see one free chair:

| # | Customer A | Customer B | Waiting room |
|---|---|---|---|
| 1 | reads count → 1 free, "I'll sit" | | 1 |
| 2 | | reads count → 1 free, "I'll sit" | 1 |
| 3 | sits | | 2 |
| 4 | | sits | **3** — one more than `N` |

**2. The lost wake-up.** A customer signals the barber, but the barber hasn't reached its wait yet.
The signal evaporates and the barber sleeps forever with a customer sitting in front of him.

**3. Half a rendezvous.** The customer walks out as soon as it's signalled and the barber starts the
next haircut before the previous customer has vacated the chair — so two customers are "in the chair"
at once, or a customer leaves with half a haircut.

## The API

```java
public class BarberShop {
    public BarberShop(int waitingChairs, int customers);
    public void solve() throws InterruptedException;
}
```

```java
new BarberShop(5, 10).solve();   // one barber, 5 waiting chairs, 10 customers
```

That's the whole public surface — the waiting room, the barber loop, and the rendezvous are all
internal. The caller picks the two sizes and presses start.

## Constraints

- **No `java.util.concurrent` collections or `Semaphore`.** The bounded waiting room is the thing
  you're building. `ReentrantLock` + `Condition` is fine; be ready to drop to `synchronized` /
  `wait` / `notifyAll`.
- Plain `java.util` for the queue itself (`LinkedList`, `ArrayDeque`) — the container isn't the
  subject.
- **No busy-waiting.** An idle barber must consume no CPU. No `Thread.sleep` standing in for
  synchronisation; sleeping to simulate the haircut is fine.

## Requirements

- **Capacity** — at most `N` customers waiting. Extras leave immediately; they never block.
- **Atomic seat claim** — testing for a free chair and taking it is one indivisible step.
- **The barber sleeps when idle** and is woken by an arriving customer, with no lost wakeup.
- **Two-way rendezvous** — a customer does not leave before its own haircut completes, and the barber
  does not begin the next before the current customer has left the chair.
- **No starvation** — every customer that sits down is eventually served. *(Strict FIFO order is the
  harder variant; this folder has both — `BarberShopEz` and the FIFO `BarberShop`.)*
- **Clean shutdown** — no customer or barber left blocked or asleep.

## Edge cases

- `N = 0` — no waiting room at all.
- `N = 1`.
- All `M` customers arriving at the same instant.
- A customer arriving exactly as the barber falls asleep.
- The barber interrupted **mid-haircut**, after the customer has committed to waiting.
- A customer interrupted while waiting for its haircut to be declared done.
- `M = 0`, or negative `N` / `M`.

## Questions to answer before you code

1. What does your capacity counter actually count — people in the waiting room, or everyone in the
   shop including the one being cut? Re-check the worked example against your answer; the balk
   threshold changes.
2. Two customers see one free chair. Which two operations must be inside one critical section?
3. A customer signals the sleeping barber before the barber reaches its wait. Is the signal lost?
   What property of your state decides that, rather than the signal itself?
4. The customer waits for "my haircut is done" and the barber waits for "the customer has left".
   Why are **both** waits necessary — what breaks if you drop either one?
5. One condition for all customers, or one per customer? Trace what a shared condition costs when
   the barber wants to wake one specific person.
6. Is FIFO order guaranteed by your design or is it incidental? Point at the exact line that
   establishes it.
7. What should a balking customer report to its caller, and is it allowed to retry?
8. Is `N = 0` legal? Who, if anyone, gets served?
9. The barber is interrupted mid-haircut. What state is the customer left in, and who resets the
   per-customer flags?
10. This is a bounded queue whose producers never block. Name the two ways it differs from a bounded
    blocking queue, and say which one forces per-customer state.
11. `solve()` owns the threads. What would the component form be — the methods a caller's own threads
    would call — and which shape is a system coding round asking for?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "leaves because the shop is full" | balking — load shedding |
| "the waiting room" | bounded queue / backlog |
| "the barber sleeps when there's nothing to do" | idle blocking; no busy-wait |
| "check for a free chair and sit in one step" | atomic test-and-claim |
| "the signal arrived before anyone was listening" | lost wakeup |
| "both sides wait for each other before moving on" | rendezvous |
| "each customer waits for *their own* haircut" | per-thread condition; synchronous request/response |
| "served in arrival order" | FIFO fairness |
| "one customer keeps getting skipped" | starvation |
| "the customer is gone and the chair is free" | hand-off completion / state reset |
| "nobody left asleep when we close" | clean termination, draining waiters |
