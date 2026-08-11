# Dining Philosophers

> Book ref: *The Little Book of Semaphores* §4.4.

> `n` philosophers sit around a round table with `n` forks, one between each pair. A philosopher
> thinks, then wants to eat, and to eat they need **both** the fork on their left and the fork on
> their right. Write the protocol so nobody gets stuck. Then tell me which deadlock condition you
> broke, and whether anyone can starve.

## What you're building

Ignore the philosophers. This is the protocol for **acquiring two locks at once without forming a
cycle**, and that situation is everywhere: transferring between two bank accounts, moving a file
between two directories (two inode locks), merging two adjacent chunk ranges of a snapshot,
rebalancing between two shards.

Every one of those has the same shape — each worker needs two of a shared pool of resources, each
resource is exclusive, and which two you need depends on who you are. The fable just makes the
topology a ring so the cycle is easy to see.

Fork `i` sits between philosopher `i` and philosopher `i+1`, so philosopher `i` needs
`fork[i]` and `fork[(i+1) % n]`.

## The failure you're designing against

Five philosophers, all reaching for their left fork at the same instant.

| Step | P0 | P1 | P2 | P3 | P4 | Table state |
|---|---|---|---|---|---|---|
| 1 | takes f0 | takes f1 | takes f2 | takes f3 | takes f4 | all 5 forks held |
| 2 | wants f1 | wants f2 | wants f3 | wants f4 | wants f0 | every fork already taken |
| 3 | blocked | blocked | blocked | blocked | blocked | **nothing will ever change** |

Each philosopher holds one fork and waits for one held by their neighbour:
`P0 → P1 → P2 → P3 → P4 → P0`. The wait-for graph has a cycle, and no thread will ever release
what it holds, because releasing only happens *after* eating.

Note what this is **not**: it isn't slowness, and it isn't unlucky scheduling. There is no future
interleaving that recovers. That distinction is the point of the problem.

## The API

```java
public class DinningPhilosopher {
    public DinningPhilosopher(int n);
    public void solve() throws InterruptedException;
}
```

```java
new DinningPhilosopher(5).solve();   // returns once every philosopher has eaten
```

`solve()` starts the philosophers, waits for all of them, and returns. A caller that returns from
`solve()` should be able to conclude that everybody ate and every fork is back on the table.

## Constraints

- **No `java.util.concurrent.Semaphore`.** Hand-roll it, or use `ReentrantLock` + `Condition`. A
  hand-rolled one already exists at `ConcurrentStructures/CustomSemaphore` — this problem is the
  reason it's there.
- No `ExecutorService` / `Executors`. Raw `Thread` is the point.
- `Thread`, `synchronized`, `wait`/`notify` are always fine.
- **No busy-waiting**, and no `Thread.sleep` used as a synchronisation mechanism. Sleeping to
  simulate thinking or eating is fine; sleeping to avoid a race is not.

## Requirements

- **Mutual exclusion** — a fork is held by at most one philosopher at a time.
- **No deadlock** — the system never reaches a state where no philosopher can make progress.
- **No starvation** — every philosopher eventually eats. Note this is strictly stronger than the
  previous point, and the two are commonly confused.
- **Concurrency** — non-adjacent philosophers must be able to eat simultaneously. A solution that
  lets exactly one philosopher eat at a time satisfies every bullet above and is still wrong.
- **Clean release** — a philosopher that fails partway through must not leave a fork on the floor.
- `solve()` returns only after all philosophers have finished.

## Edge cases

- `n = 1` — trace `left` and `right` for philosopher 0 before assuming this works.
- `n = 2` — both philosophers share both forks.
- `n = 0`, or negative.
- A philosopher interrupted while holding exactly one fork.
- A philosopher interrupted while holding both.
- Two adjacent philosophers wanting to eat repeatedly while one between them never gets a turn.

## Questions to answer before you code

1. Name the four Coffman conditions. Which one does your solution break? Could you have broken a
   different one instead, and what would that have cost?
2. In the trace above, what precisely distinguishes deadlock from "very slow"? Write the test you'd
   apply to a stuck system to tell them apart.
3. If your solution caps the number of philosophers allowed to reach for forks, why does that cap
   work? Give the counting argument, not the intuition.
4. Whatever cap you chose — would one lower also work? Would it be better or worse, and on which axis?
5. Making a single philosopher reach right-then-left also fixes it. Which condition does *that* break,
   and how does its concurrency compare to a cap?
6. "Take the left fork; try for the right; if it's busy, put the left one back and retry" also avoids
   deadlock. What new failure mode does it introduce, and why is that failure harder to detect?
7. Does your solution guarantee that every philosopher eventually eats, or only that the table never
   freezes? Re-read the requirements — which one was actually asked for?
8. What happens when `n = 1`? Compute `left` and `right`. What about `n = 2`?
9. A philosopher is interrupted holding exactly one fork. What must be true about where your release
   code lives — and does the same hold for whatever admission control you added?
10. This is written as a simulation that runs once and finishes. What would the same thing look like
    as a component other code calls — `acquire(i)` / `release(i)` — and which of the two is a system
    coding round actually asking for?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "everyone holds one and waits for the next" | circular wait |
| "nobody can move, ever" | deadlock |
| "everyone keeps moving but nobody progresses" | livelock |
| "one unlucky philosopher never eats" | starvation |
| "the four things that must all be true for a deadlock" | Coffman conditions |
| "who is waiting for whom" | wait-for graph |
| "only `n-1` allowed to reach at once" | admission control — the waiter/footman; breaks hold-and-wait |
| "always take the lower-numbered fork first" | resource ordering / lock ordering; breaks circular wait |
| "take one, try the other, put it back if busy" | try-and-backoff |
| "there's a limit on how long you might wait" | bounded waiting |
| "a fork" | a mutex, or a binary semaphore |
| "non-adjacent philosophers eat together" | concurrency — distinct from correctness |
