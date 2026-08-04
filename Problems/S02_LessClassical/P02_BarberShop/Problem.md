# The Barbershop

> Book ref: _The Little Book of Semaphores_ §5.2.

## Problem

A barbershop has **one barber**, one barber chair, and a waiting room with **`N` chairs**.

- If there are **no customers**, the barber sits in the barber chair and **sleeps**.
- A customer who arrives:
  - If the barber is **asleep**, wakes the barber and gets a haircut.
  - If the barber is **busy** but a waiting chair is free, **sits and waits**.
  - If the barber is busy **and all `N` waiting chairs are full**, the customer **leaves** (balks).
- The barber gives haircuts one at a time; when done with one customer, he takes the next waiting
  customer, or sleeps if none.

### API (typical)

```java
class Barbershop {
    void barber();               // loop: sleep if empty, else cut next customer's hair
    boolean customer(int id);    // returns false if it balked (shop full), true if it got a cut
}
```

### Requirements

- **Capacity limit** — at most `N` customers waiting; extras leave.
- **No lost customer** — a customer that sits down must eventually be served (in the fair variant).
- **Rendezvous** — barber and customer must agree "haircut is happening": the customer shouldn't
  leave before it's cut, the barber shouldn't start the next before finishing the current.
- **No busy-waiting.**

## The trap

Two independent rendezvous have to line up:

1. **Wake-up:** customer signals a sleeping barber; but if the customer signals _before_ the barber
   is actually waiting, the signal is lost → barber sleeps forever. (Guard with a count + `while`.)
2. **Haircut hand-off:** the customer must not walk out until the haircut is done, and the barber
   must not grab the next customer until the current one is finished. Both sides wait on each other.

## Points to Ponder

- **Capacity check must be atomic with sitting down.** Reading "seats free?" and then taking a seat
  must be one critical section, or two customers race into the last chair. This is the counting part.
- **The wake-up race.** How do you avoid the lost-wakeup when a customer arrives just as the barber
  is falling asleep? (A `waiting` counter checked in a `while` loop, not a bare `signal`.)
- **Fair (FIFO) vs unfair.** The basic book version doesn't guarantee order — a customer can be
  skipped repeatedly (starvation). A **FIFO** version needs a queue and a per-customer condition (or
  a ticket/turnstile) so customers are served in arrival order. (This repo implements the FIFO
  version.)
- **Semaphore vs monitor.** Book uses `customers`, `barbers`, `mutex`, and two rendezvous semaphores
  (`customerDone`, `barberDone`). A monitor version uses a `Queue` + per-customer `Condition`s. Trade
  offs?
- **Balking semantics.** What exactly does a full shop return/throw? Should the customer retry later,
  or leave for good?
- **Generalization — Hilzer's Barbershop (§5.3).** Multiple barbers, batching (barber cuts a group),
  and strict FIFO. What changes when there are `K` barbers?
- **Relation to bounded blocking queue.** Waiting room = bounded buffer, customers = producers,
  barber = consumer — but with **balking** (non-blocking `offer`) and a **two-way rendezvous** on top.
- **Termination.** How do the barber and customers shut down without leaving anyone blocked on a
  wait or asleep forever?
