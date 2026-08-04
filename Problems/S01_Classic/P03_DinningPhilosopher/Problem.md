# Dining Philosophers

> Book ref: _The Little Book of Semaphores_ §4.4.

## Problem

`n` philosophers sit around a table; `n` forks, one between each pair. A philosopher alternates
between **thinking** and **eating**, and needs **both** the fork on their left and right to eat.
Design a protocol so all philosophers can eat without getting stuck.

Fork `i` sits between philosopher `i` and philosopher `i+1`. So philosopher `i` uses:

- left = `fork[i]`, right = `fork[(i+1) % n]`.

### API

```java
new DinningPhilosopher(n).solve();
```

### Requirements

- **No deadlock** — the system never freezes with everyone waiting.
- **No starvation** — every philosopher eventually eats.
- **Mutual exclusion** — a fork is held by at most one philosopher at a time.
- Allow **concurrency** — non-adjacent philosophers should be able to eat at the same time.

## The trap

If everyone grabs **left, then right** in the same order, they can all grab their left fork at once,
then wait forever for a right fork nobody will release → **circular-wait deadlock**.

## Points to Ponder

- **Which deadlock condition do you break?** Coffman's four: mutual exclusion, hold-and-wait,
  no-preemption, circular-wait. You only need to break **one**.
- **Solutions to compare:**
  - _Waiter / footman_ — cap diners at `n-1` (this repo's approach); breaks hold-and-wait.
  - _Resource ordering_ — one philosopher picks right-then-left (asymmetry); breaks circular-wait.
  - _Tanenbaum / state-based_ — a philosopher eats only when neither neighbor is eating; can be
    made starvation-free.
  - _`tryLock` + backoff_ — grab left, try right, drop left on failure; risks **livelock**.
- **Deadlock vs livelock vs starvation** — know the difference and which each solution risks.
- **Fairness** — does your solution bound how long a philosopher waits, or just avoid deadlock?
- **Why does capping at `n-1` work?** Pigeonhole: with ≤ `n-1` competing for `n` forks, at least
  one philosopher can always get both.
