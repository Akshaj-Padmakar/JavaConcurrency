# Cigarette Smokers — Revision Sheet

> Book ref: _The Little Book of Semaphores_ §4.5.

## One-line idea

Agent (fixed, dumb) puts **two** of {tobacco, paper, matches} on the table and signals raw
availability. The smoker holding the **third** must smoke. You can't make the agent smart, so add
**pusher** threads that turn "two ingredients available" into "wake exactly the right smoker."

## Why pushers (the whole trick)

- The agent may **not** be modified and only signals per-ingredient availability — it can't say
  "wake the tobacco smoker."
- Pushers are **separate threads you add** (not inside the agent). Each pusher owns one ingredient's
  flag, and when it sees the **other** ingredient already present, it deduces the unique smoker and
  signals it.
- This is the adapter pattern between an immutable signal source (OS / library / hardware IRQ) and
  the smart routing you need. The agent stays dumb; all "who wins" logic lives in code the agent
  has no reference to.

## Pusher routing (verify all 3 cases)

| Agent places      | Missing | Smoker who smokes | Pusher deduction                    |
| ----------------- | ------- | ----------------- | ----------------------------------- |
| matches + paper   | tobacco | **tobacco** smoker| pusher sees its own + the other one |
| tobacco + paper   | matches | **matches** smoker| →                                   |
| tobacco + matches | paper   | **paper** smoker  | →                                   |

`signal()` (not `signalAll()`) is correct: each smoker/pusher waits on its **own** condition for a
**unique** predicate.

## Why the single-round version is correct

`while`-guarded predicates everywhere ⇒ even a **lost signal** (fired before the target `await`ed) is
safe: the smoker re-checks its flags and proceeds. Flags are only reset on completion, so the right
smoker always sees its pair and smokes. Termination is clean via a `stop` flag broadcast on every
condition.

## Looping version — it's NOT just "add while loops"

The mechanical part (loops, drop `stop`, reset flags) is trivial. Two **coupling bugs** are the hard
part — both pass a naive single-round test and only surface under load.

### 🔴 Pitfall 1 — pusher busy-fires if only the smoker resets flags

If the pusher loops on `while (!tobacco) await()` and only the **smoker** clears the flag, the pusher
signals the smoker, loops back, and finds `tobacco` **still true** (smoker hasn't run yet) → signals
again and again, spinning until the smoker is scheduled. Resolves (while-guard absorbs it) but it's a
CPU-burning livelock-y race.

**Fix — the pusher must _consume_ what it observed**, so it re-blocks immediately:

```java
while (!tobacco) tobaccoPusherCond.await();
tobacco = false;                                   // consume MY ingredient
if      (paper)   { paper = false;   matchesSmokerReady = true; matchesSmokerCond.signal(); }
else if (matches) { matches = false; paperSmokerReady   = true; paperSmokerCond.signal();   }
```

(This is exactly why the book gives pushers their own counters — to consume, not just observe.)

### 🔴 Pitfall 2 — "all flags false" is the WRONG agent back-pressure

Once pushers consume, flags go `false` at **consume time — before the smoker actually smokes**. If
the agent waits on "all flags false," it produces the next pair while the previous smoker is still
mid-smoke → **overlapping rounds**. The flags answer "ingredients gone?"; the agent needs "**smoker
finished?**" — a different event.

**Fix — an explicit "smoke done" signal, decoupled from the ingredient flags:**

```java
Semaphore agentSem = new Semaphore(1);   // 1 = agent may run one round

// Agent
agentSem.acquire();                      // wait for the PREVIOUS smoke to finish
lock.lock(); try { /* place 2 ingredients, signal their 2 pushers */ } finally { lock.unlock(); }

// Smoker
lock.lock();
try { while (!mySmokerReady) mySmokerCond.await(); mySmokerReady = false; }
finally { lock.unlock(); }
// ...smoke...
agentSem.release();                      // NOW the agent may produce again
```

> Note the tension: pusher-consumes ⇒ agent can't gate on flags (Pitfall 2). Smoker-consumes ⇒
> pusher re-fires (Pitfall 1). You need **both** fixes: pusher consumes, agent gated on a separate
> "done" signal.

## Things to keep in mind

- **Don't modify the agent** — it only emits raw availability; routing lives in the pushers.
- **`while`-guard every `await()`** — makes lost/stray signals harmless.
- **One condition per thread** ⇒ targeted `signal()` beats `signalAll()`.
- **Single global lock** here already gives atomic flag check-and-set, so the classic pusher-mutex
  race is excluded — the remaining hazards are the two coupling bugs above.
- **Clean shutdown / interrupts:** a `stop` broadcast on all conditions unblocks every `await`. Don't
  let one interrupted smoker tear down the whole system; restore the interrupt flag.

## 30-second recall

> Agent (fixed) drops 2 ingredients + signals availability; **pushers** deduce and wake the one
> smoker holding the 3rd. Looping = trivial EXCEPT: (1) **pusher must consume** the ingredients or it
> busy-fires; (2) **agent back-pressure needs a separate "smoke done" signal**, not "flags all
> false" (flags clear before smoking finishes). `while`-guard everything; `signal()` per unique
> condition.
