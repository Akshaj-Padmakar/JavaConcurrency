# The Unisex Bathroom Problem

> Book ref: _The Little Book of Semaphores_ §6.2.

## Problem

A single bathroom is shared by **men** and **women** threads.

- Any number of people of the **same** gender may be in the bathroom **at the same time**.
- **Never** allow a man and a woman inside **simultaneously**.
- **No perpetual blocking**: if there is a steady stream of women entering and leaving, and a man is
  waiting, he must **eventually** get in (and vice versa). A gender that currently "owns" the
  bathroom cannot hog it forever just because its own threads keep arriving.

### API (typical)

```java
class UnisexBathroom {
    void manEnter(int id)   throws InterruptedException;
    void womanEnter(int id) throws InterruptedException;
    void exit(int id)       throws InterruptedException;
}
```

## The trap

Mutual exclusion **between genders** is the easy half — a simple "current gender" flag with a
per-gender occupant count gets you that far. The hard half is the **no-perpetual-blocking**
requirement: if you let every arriving thread of the current gender join in as long as one of its
own is already inside, a continuous stream of women can keep the bathroom "women-only" forever,
starving any waiting man. Checking "is anyone of the other gender waiting?" only at the moment the
last occupant leaves is not enough either — a woman who arrives *after* that check but *before* the
gender actually switches can sneak back in and restart the streak.

## The core idea

You need an explicit **turnover point**: some rule that forces a switch away from the current gender
even while members of that gender are still willing to enter. `UnisexBathroom.java` picks
**batching** — each gender is only allowed to admit up to a fixed `BATCH_SIZE` before the bathroom is
forced to consider switching, and only up to `MAX_CAPACITY` occupants at once. Once the batch limit
is hit (or the queue for that gender drains), and the room empties out, control passes to whichever
gender is waiting — preferring the *other* gender if anyone is queued there, otherwise letting the
same gender start a fresh batch.

This is one of several valid strategies (see the header comment in `UnisexBathroom.java` for the
full list the author considered):

| Strategy          | Fairness            | Throughput |
| ------------------ | -------------------- | ---------- |
| Always prefer current gender | Starves the other gender | Best |
| Strict FIFO         | Perfectly fair        | Worst — no same-gender parallelism |
| Turnstile            | Fair                  | Bad |
| **Batching (chosen)** | Bounded wait          | High |
| Phasing (time-boxed) | Bounded wait          | High |

## Points to Ponder

- **Why isn't "let anyone of the current gender in" enough?** It satisfies mutual exclusion but not
  the anti-starvation clause — nothing ever forces a switch. You need a condition that is guaranteed
  to become true even under an infinite stream of one gender (a count, a timer, or an explicit
  queue-drain check).
- **Batch size vs. concurrency cap.** `BATCH_SIZE` bounds how many people *total* get admitted before
  a forced turnover check; `MAX_CAPACITY` bounds how many are *inside at once*. These are independent
  knobs — the classic book problem doesn't have a capacity cap at all (any number of the same gender
  may be inside together); it's an extra generalization added here. What breaks if `MAX_CAPACITY` is
  removed (set to infinity)?
- **Who decides to switch, and when?** Switching only makes sense once the room is fully empty of the
  outgoing gender (`insideCnt == 0`) — otherwise you'd have men and women inside at once. Where in the
  code is that guarantee enforced, and what happens if you check the wrong counter?
- **What if the "preferred" queue is empty at switch time?** If nobody of the other gender is
  waiting, you fall back to giving the current gender another batch. Does your reset logic correctly
  distinguish "give current gender a new batch" from "go idle" — and can the bathroom get stuck
  believing a gender still "owns" it after both queues have drained?
- **Signal targeting.** Each waiting thread should be woken via its *own* condition once it's
  actually eligible to proceed — not via `signalAll()`, which would wake everyone only for most to
  re-check and go back to sleep. How many threads should a single hand-off wake at once (one, or up
  to a batch's worth)?
- **Queue bookkeeping under concurrent wakeups.** If several threads are signalled at once (a batch
  hand-off), do they leave the waiting queue in the order they were signalled, or could a later
  arrival's lock-acquisition race let it remove the wrong entry from the front of the queue?
- **Fairness within a gender.** Batching solves cross-gender starvation — does it also guarantee that
  *within* one gender's queue, the longest-waiting thread of that gender is served first?
- **Termination.** With a fixed number of men and women (as in `solve()`), does the bathroom ever
  reach a state where it's waiting to be signalled by a thread that will never arrive?
