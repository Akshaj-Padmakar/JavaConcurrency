# Cigarette Smokers

> Book ref: _The Little Book of Semaphores_ §4.5.

## Problem

Three smokers and one agent share a table. To smoke you need three ingredients: **tobacco, paper,
matches**. Each smoker has an **infinite supply of exactly one** ingredient and lacks the other two:

- Smoker T has tobacco, needs paper + matches.
- Smoker P has paper, needs tobacco + matches.
- Smoker M has matches, needs tobacco + paper.

The **agent** repeatedly places **two different** ingredients on the table, then waits. The smoker
who has the **third** ingredient must pick up the two, roll a cigarette, smoke, and signal the agent
to go again.

Design the synchronization so the **correct** smoker is woken each round — without deadlock.

### Constraint (this is the whole problem)

- You **may not modify the agent** (think of it as fixed library code).
- The agent only knows how to `signal` that ingredients are available — it does **not** know which
  smoker should react.

## The trap

If smokers wait directly on "is my ingredient present?", two smokers can wake on overlapping
signals (e.g. both the tobacco and paper flags), grab ingredients they shouldn't, and **deadlock** —
each holding one item the other needs. The agent signaling raw ingredient availability isn't enough
to pick the unique correct smoker.

## The key idea: pusher threads

Introduce three **pushers** (one per ingredient) that you _are_ allowed to add. The agent signals
pushers; each pusher records "my ingredient is on the table" and checks what's **already** there:

- If a pusher sees the other two ingredients already present, it knows exactly **one** smoker can
  complete a cigarette, and signals **that** smoker.

The pushers turn "two ingredients available" into "wake exactly the right smoker."

## Points to Ponder

- **Why can't smokers wait on the agent directly?** The agent's signal is ambiguous about which
  smoker should act; pushers disambiguate by tracking combined state.
- **Why one pusher per ingredient?** Each pusher is the single writer of its own flag and can read
  the other two → it can deduce the unique missing-ingredient smoker.
- **`signal` vs `signalAll`** — each smoker/pusher waits on its **own** condition for a **unique**
  predicate, so a targeted `signal` is correct and cheaper than `signalAll`.
- **Generalized version** — if the agent can place ingredients in patterns not known in advance,
  you need a more general readiness check (count-based) instead of hard-coded pairs.
- **Shutdown / termination** — how do waiting threads exit cleanly? (A `stop` flag broadcast on all
  conditions so no thread is left blocked on `await()`.)
- **Starvation / fairness** — over many rounds, does every smoker get a turn, or can the agent's
  random choices starve one?
