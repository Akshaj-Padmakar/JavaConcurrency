# Cigarette Smokers

> Book ref: *The Little Book of Semaphores* §4.5.

> Three smokers sit at a table. Each has an unlimited supply of exactly one ingredient and needs the
> other two: one has tobacco, one has paper, one has matches. An agent puts **two different**
> ingredients on the table and waits. Whichever smoker holds the third picks them up, rolls, smokes,
> and tells the agent to go again. **You may not change the agent** — it can only announce that
> ingredients are available; it has no idea which smoker that means. Make the right smoker wake up,
> every time, without deadlock.

## What you're building

Strip the fable away and this is **event demultiplexing**: a source emits one undifferentiated
"something changed" signal, several handlers are listening, exactly one of them is now able to run —
and the source doesn't know which one.

That shape is everywhere. A chunk arrives and some reassembly job's last dependency is now satisfied,
but the arrival event doesn't know which job. A file handle closes and exactly one waiter's full
precondition is met. An interrupt fires and the kernel must route it to one driver.

The load-bearing constraint is **"you may not modify the agent."** Without it this is ordinary
producer–consumer and there's no problem to solve. With it, you're writing an **adapter** between a
signal that carries too little information and a wakeup that must be exactly precise. That is the
entire exercise, and it's why this problem exists separately from the bounded buffer.

The agent, as given:

```java
// FIXED — you do not get to change this.
lock.lock();
try {
    // put two different ingredients on the table
    signalIngredientsAvailable();     // "something is on the table". That's all it says.
} finally { lock.unlock(); }
```

## The failure you're designing against

The obvious approach — each smoker waits for the ingredients it personally needs — deadlocks.
Agent puts out **tobacco + paper**, so the matches-smoker is the one who should eat:

| # | Actor | Action | Table | State |
|---|---|---|---|---|
| 1 | Agent | places tobacco, paper | `{T, P}` | smoker-M is the correct one |
| 2 | smoker-T | needs paper+matches; sees paper, **takes it** | `{T}` | holds paper, still needs matches |
| 3 | smoker-P | needs tobacco+matches; sees tobacco, **takes it** | `{ }` | holds tobacco, still needs matches |
| 4 | smoker-M | wakes, table is empty | `{ }` | the one who could have smoked gets nothing |
| 5 | — | nobody finishes, so nobody signals the agent | `{ }` | **deadlock** |

Two smokers are each holding one thing and waiting for a third that will never come, because the
agent only moves after someone smokes. Note that no smoker did anything *wrong* — each grabbed an
ingredient it genuinely needed. The bug is that a partial match was allowed to consume state.

## The API

```java
public class CigratteSmoker {
    public CigratteSmoker();
    public void solve() throws InterruptedException;
}
```

```java
new CigratteSmoker().solve();   // runs the table; returns when the session ends
```

## Constraints

- **The agent is fixed.** It announces availability and nothing else. It cannot be told which smoker
  to wake, cannot be given per-smoker conditions, and cannot inspect the table.
- **You may add whatever else you like** — extra threads, extra state, extra conditions. The agent
  and the three smokers are the fixed cast; anything between them is yours.
- No `java.util.concurrent` collections. `ReentrantLock` + `Condition` is fine; be ready to drop to
  `synchronized` / `wait` / `notifyAll`. Hand-roll a semaphore if you want one.
- **No busy-waiting**, and no `Thread.sleep` used to dodge a race.

## Requirements

- Exactly **one** smoker wakes per round, and it is the one holding the third ingredient.
- **Atomic pickup** — a smoker takes both ingredients or neither. A partial take is what causes the
  trace above.
- **No deadlock**, and no lost signal if the agent announces before anyone is waiting.
- The agent goes again only after a smoker has finished, and the table is empty when it does.
- Runs for **many rounds**, not one.
- **Clean shutdown** — when the session ends, no thread is left parked on a condition.
- No busy-waiting.

## Edge cases

- The agent signals before any smoker or helper has started waiting.
- The agent picks the same pair twice in a row.
- Shutdown requested while two smokers are parked and one is mid-round.
- A thread interrupted while holding the lock or waiting on a condition.
- The agent is much faster than the smokers.

## Questions to answer before you code

1. The agent's signal means "ingredients are on the table" and nothing more. Why can't the three
   smokers simply wait on it? Write the interleaving that breaks — don't just assert that it does.
2. In that failing trace, which Coffman condition is present? Could you fix it purely by changing the
   order in which a smoker picks things up?
3. You're allowed to add threads. What is the **minimum** number, and what is each one responsible
   for? Why does that number and not fewer?
4. Whatever you add in the middle: how does it determine which single smoker to wake? What does it
   know that an individual smoker does not?
5. The three smokers have three **different** predicates. Does that argue for one condition each, or
   one shared condition? Trace what the wrong choice costs you — a hang, or just wasted wakeups?
6. `signal` or `signalAll` on the smoker side? Answer from the **shape of the predicate**, not from
   how many threads happen to be waiting.
7. After a smoker takes the two ingredients, what must be true of the table before the agent runs
   again — and whose job is it to make that true?
8. How many rounds does your design run? What must change for it to loop indefinitely, and what
   breaks first when you make that change?
9. How does a parked thread ever exit? Sketch the shutdown path and say which signalling method it
   requires and why.
10. This is written as a simulation with a `solve()`. What is the component version — what would the
    API look like if another system drove the rounds?

## Jargon

| The plain phrasing | The term to use out loud |
|---|---|
| "one vague signal, several possible handlers" | event demultiplexing |
| "work out which single handler is now able to run" | readiness determination |
| "wake exactly one specific thread" | targeted signal |
| "the place a thread waits for one specific thing" | condition variable, wait-set |
| "take both or take neither" | atomic acquisition |
| "each holds a piece, none can finish" | deadlock — hold-and-wait |
| "the signal arrived before anyone was listening" | lost wakeup |
| "code you're not allowed to change" | fixed producer / third-party library |
| "re-check after waking" | guarded suspension, predicate loop |
| "one smoker never gets a turn" | starvation |
| "everyone wakes, one proceeds, the rest sleep again" | thundering herd |
