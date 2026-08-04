# Dining Savages

> Book ref: _The Little Book of Semaphores_ §5.1.

## Problem

A tribe of savages eats from a shared pot that holds `M` servings. There are many **savage** threads
and one **cook** thread.

- A savage who wants to eat takes **one serving** from the pot — but only if the pot is **not empty**.
- If a savage finds the pot **empty**, they **wake the cook** and wait until the pot is refilled.
- The **cook** refills the pot back to `M` servings, then goes back to sleep until woken again.

### Rules

- Savages **cannot** take a serving when the pot is empty.
- The cook **cannot** refill until the pot is empty (refill happens only when signalled by an empty
  pot).
- Only **one** savage decrements the pot at a time (the count must not be corrupted).

### API (typical)

```java
class DiningSavages {
    void savageEat(int id) throws InterruptedException; // take a serving (or wake cook + wait)
    void cookRefill()      throws InterruptedException; // sleep until empty, then fill to M
}
```

## The trap

The pot count is shared mutable state read-modified-written by many savages. Naively "if empty, wake
cook" outside a lock races: two savages can both see `servings == 1`, both decrement → `-1`. And the
cook must refill **exactly once** per empty event, not once per savage that noticed.

## Points to Ponder

- **Who resets the count — cook or savage?** The canonical solution has the savage that empties the
  pot signal the cook; the cook fills to `M`. Keep the decrement and the "am I the one who emptied
  it?" check **atomic** (same lock/mutex).
- **Signalling direction.** Savage → cook: "pot empty, please cook." Cook → savage(s): "pot full,
  resume." How do you avoid a lost wakeup if the cook signals before savages wait? (Guard with a
  predicate in a `while` loop.)
- **`Semaphore` vs monitor.** The book uses two semaphores (`emptyPot`, `fullPot`) plus a `mutex`
  for the counter. Could you do it with a `ReentrantLock` + `Condition`s? What's cleaner?
- **Only one refill per empty event.** Multiple savages may find the pot empty around the same time —
  ensure the cook refills once, not N times. (Signal the cook only from the savage that took the
  *last* serving.)
- **Fairness / starvation.** Can one savage repeatedly grab servings and starve others? Do fair
  semaphores or a queue help?
- **Generalization — bounded producer/consumer.** This is a producer (cook) / multi-consumer
  (savages) problem with a **batch refill** instead of one-at-a-time production. How does it relate
  to a blocking queue? What changes because production is "fill to M" not "add 1"?
- **Multiple cooks?** If refilling `M` servings is slow, would multiple cooks help, and how do you
  keep "refill only when empty" correct with more than one?
- **Termination.** How do savages and the cook shut down cleanly without leaving anyone blocked on a
  wait?
