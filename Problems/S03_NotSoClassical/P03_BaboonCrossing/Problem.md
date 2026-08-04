# The Baboon Crossing Problem

> Book ref: _The Little Book of Semaphores_ §6.3.
> Same shape as [P02_UnisexBathroom](../P02_UnisexBathroom/Problem.md) + a rope capacity cap.

## Setup

- Baboons cross a canyon on one rope, **left↔right**.
- Rope holds at most `N` baboons **at once** (capacity limit).
- Opposite directions can **never** be on the rope together (they fight).
- **No starvation** — a steady stream one way must not block the other way forever.

## The trap

- Capping "one direction at a time" is easy; capping **and** avoiding starvation is not.
- Need a hard trigger that forces a direction switch even while same-direction baboons keep arriving.

## Key idea

- Two counters: `onRopeCount` (live, ≤ `MAX_CAPACITY`) and `batchCount` (total this batch, ≤
  `BATCH_SIZE`).
- Switch direction only when `onRopeCount == 0` **and** (`batchCount == BATCH_SIZE` **or** own
  queue drained early).
- On switch: prefer opposite queue if non-empty, else restart same direction.

## Ponder

- Why two separate caps (`MAX_CAPACITY` vs `BATCH_SIZE`) instead of one?
- Why must the rope be fully empty before switching direction?
- Should "batch exhausted" and "own queue drained early" behave identically once empty?
- If **both** queues are empty at a switch — does direction correctly fall back to `NONE`, or can it
  get stuck pointing at a direction nobody's using?
- Turnover wakes up to `MAX_CAPACITY` waiters at once — does anything assume they reacquire the lock
  in queue order?
- Does batching guarantee FIFO fairness *within* one direction, or just bounded cross-direction wait?
- Remove `MAX_CAPACITY` entirely — does this reduce to exactly the bathroom problem?
- With fixed baboon counts, can the system end up waiting on a signal that never comes?
